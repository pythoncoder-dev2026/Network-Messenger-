package com.network.messenger.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline Bluetooth/BLE + Wi-Fi nearby mesh transport powered by Google Nearby Connections
 * using Strategy.P2P_CLUSTER for multi-peer topologies.
 */
class NearbyConnectionsTransport(
    private val context: Context,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : Transport {

    override val type: TransportType = TransportType.NEARBY

    private val client by lazy { Nearby.getConnectionsClient(context) }

    private val _state = MutableStateFlow(TransportState(type = TransportType.NEARBY))
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, PeerInfo>()
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 128)
    override val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    private var localEndpointName: String = "node"
    @Volatile
    private var running: Boolean = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                if (bytes.size > MAX_PAYLOAD_BYTES) {
                    logRepository.warn(
                        LogCategory.TRANSPORT,
                        "[BT/BLE] Dropped oversized frame from $endpointId (${bytes.size}B)"
                    )
                    return
                }
                scope.launch {
                    _incomingFrames.emit(
                        IncomingFrame(
                            sourceTransport = TransportType.NEARBY,
                            sourcePeerId = endpointId,
                            payloadBytes = bytes
                        )
                    )
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            peerMap[endpointId] = PeerInfo(
                peerId = endpointId,
                userId = info.endpointName.removePrefix("nm:"),
                displayName = info.endpointName,
                transport = TransportType.NEARBY,
                connected = false
            )
            publishPeers()
            logRepository.info(
                LogCategory.PEER,
                "[BT/BLE] Connection initiated: endpoint=$endpointId name=${info.endpointName} (auto-accepting cluster link)"
            )
            try {
                client.acceptConnection(endpointId, payloadCallback)
            } catch (e: SecurityException) {
                logRepository.error(LogCategory.PERMISSION, "[BT/BLE] acceptConnection denied: ${e.message}")
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val existing = peerMap[endpointId]
                    peerMap[endpointId] = (existing ?: PeerInfo(
                        peerId = endpointId,
                        displayName = endpointId,
                        transport = TransportType.NEARBY,
                        connected = true
                    )).copy(connected = true, lastSeenMs = System.currentTimeMillis())
                    publishPeers()
                    logRepository.success(
                        LogCategory.PEER,
                        "[BT/BLE] Peer connected: endpoint=$endpointId total=${connectedCount()}"
                    )
                }
                else -> {
                    peerMap.remove(endpointId)
                    publishPeers()
                    logRepository.warn(
                        LogCategory.PEER,
                        "[BT/BLE] Connection failed: endpoint=$endpointId code=${result.status.statusCode}"
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            peerMap.remove(endpointId)
            publishPeers()
            logRepository.warn(
                LogCategory.PEER,
                "[BT/BLE] Peer lost: endpoint=$endpointId remaining=${connectedCount()}"
            )
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (peerMap.containsKey(endpointId)) return
            peerMap[endpointId] = PeerInfo(
                peerId = endpointId,
                userId = info.endpointName.removePrefix("nm:"),
                displayName = info.endpointName,
                transport = TransportType.NEARBY,
                connected = false
            )
            publishPeers()
            logRepository.info(
                LogCategory.PEER,
                "[BT/BLE] Peer discovered: endpoint=$endpointId name=${info.endpointName}"
            )
            // Tie-breaker: higher endpoint name requests connection first to reduce collision storms,
            // while still allowing symmetric fallback if not yet connected.
            try {
                client.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { err ->
                        logRepository.warn(
                            LogCategory.PEER,
                            "[BT/BLE] requestConnection to $endpointId deferred: ${err.message}"
                        )
                    }
            } catch (e: SecurityException) {
                logRepository.error(LogCategory.PERMISSION, "[BT/BLE] requestConnection permission error: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val peer = peerMap[endpointId]
            if (peer != null && !peer.connected) {
                peerMap.remove(endpointId)
                publishPeers()
            }
            logRepository.info(LogCategory.PEER, "[BT/BLE] Endpoint out of discovery range: $endpointId")
        }
    }

    override suspend fun start(localNodeId: String, subscribedNetworkIds: Set<String>) {
        if (running) return
        if (!PermissionHelper.hasNearbyPermissions(context)) {
            _state.value = TransportState(
                type = TransportType.NEARBY,
                status = TransportStatus.ERROR,
                detail = "Missing BT/Nearby permissions"
            )
            logRepository.error(
                LogCategory.PERMISSION,
                "[BT/BLE] Cannot start Nearby Connections: Bluetooth/Nearby permissions denied"
            )
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btEnabled = btManager?.adapter?.isEnabled == true
        if (!btEnabled) {
            _state.value = TransportState(
                type = TransportType.NEARBY,
                status = TransportStatus.DEGRADED,
                detail = "Bluetooth is OFF"
            )
            logRepository.warn(
                LogCategory.TRANSPORT,
                "[BT/BLE] Bluetooth adapter is OFF — Nearby will run degraded until Bluetooth is enabled"
            )
        } else {
            _state.value = TransportState(
                type = TransportType.NEARBY,
                status = TransportStatus.STARTING,
                detail = "Starting P2P_CLUSTER"
            )
        }

        localEndpointName = "nm:${CryptoManager.shortId(localNodeId)}"
        running = true

        try {
            val advOptions = AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
            val discOptions = DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()

            client.startAdvertising(localEndpointName, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .addOnSuccessListener {
                    logRepository.success(
                        LogCategory.TRANSPORT,
                        "[BT/BLE] Nearby advertising started (P2P_CLUSTER, service=$SERVICE_ID)"
                    )
                    updateActiveState()
                }
                .addOnFailureListener { err ->
                    logRepository.error(LogCategory.TRANSPORT, "[BT/BLE] Advertising failed: ${err.message}")
                    _state.update { it.copy(status = TransportStatus.ERROR, detail = err.message ?: "Adv failed") }
                }

            client.startDiscovery(SERVICE_ID, discoveryCallback, discOptions)
                .addOnSuccessListener {
                    logRepository.success(LogCategory.TRANSPORT, "[BT/BLE] Nearby discovery started (P2P_CLUSTER)")
                    updateActiveState()
                }
                .addOnFailureListener { err ->
                    logRepository.error(LogCategory.TRANSPORT, "[BT/BLE] Discovery failed: ${err.message}")
                }
        } catch (e: SecurityException) {
            running = false
            _state.value = TransportState(
                type = TransportType.NEARBY,
                status = TransportStatus.ERROR,
                detail = "Permission denied"
            )
            logRepository.error(LogCategory.PERMISSION, "[BT/BLE] SecurityException starting Nearby: ${e.message}")
        } catch (e: Exception) {
            running = false
            _state.value = TransportState(
                type = TransportType.NEARBY,
                status = TransportStatus.ERROR,
                detail = e.message ?: "Error"
            )
            logRepository.error(LogCategory.ERROR, "[BT/BLE] Nearby start error: ${e.message}")
        }
    }

    override suspend fun stop() {
        if (!running && _state.value.status == TransportStatus.STOPPED) return
        running = false
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        peerMap.clear()
        publishPeers()
        _state.value = TransportState(
            type = TransportType.NEARBY,
            status = TransportStatus.STOPPED,
            detail = "Stopped"
        )
        logRepository.info(LogCategory.TRANSPORT, "[BT/BLE] Nearby Connections transport stopped")
    }

    override suspend fun updateSubscribedNetworks(networkIds: Set<String>) = Unit

    override suspend fun broadcast(payloadBytes: ByteArray, excludePeerId: String?): Int {
        if (!running) return 0
        val targets = peerMap.values
            .filter { it.connected && it.peerId != excludePeerId }
            .map { it.peerId }
        if (targets.isEmpty()) return 0

        return try {
            client.sendPayload(targets, Payload.fromBytes(payloadBytes))
            targets.size
        } catch (e: Exception) {
            logRepository.error(LogCategory.TRANSPORT, "[BT/BLE] Broadcast send failed: ${e.message}")
            0
        }
    }

    override suspend fun sendToPeer(peerId: String, payloadBytes: ByteArray): Boolean {
        if (!running) return false
        val peer = peerMap[peerId] ?: return false
        if (!peer.connected) return false
        return try {
            client.sendPayload(peerId, Payload.fromBytes(payloadBytes))
            true
        } catch (e: Exception) {
            logRepository.error(LogCategory.TRANSPORT, "[BT/BLE] Send to $peerId failed: ${e.message}")
            false
        }
    }

    fun onBluetoothStateChanged(enabled: Boolean) {
        if (!running) return
        if (!enabled) {
            _state.update { it.copy(status = TransportStatus.DEGRADED, detail = "Bluetooth OFF") }
            logRepository.warn(LogCategory.TRANSPORT, "[BT/BLE] Bluetooth turned OFF — active Nearby links may drop")
        } else {
            updateActiveState()
            logRepository.success(LogCategory.TRANSPORT, "[BT/BLE] Bluetooth restored — Nearby P2P_CLUSTER active")
        }
    }

    private fun updateActiveState() {
        if (!running) return
        _state.value = TransportState(
            type = TransportType.NEARBY,
            status = TransportStatus.ACTIVE,
            detail = "P2P_CLUSTER (${connectedCount()} peers)",
            peerCount = connectedCount()
        )
    }

    private fun publishPeers() {
        val list = peerMap.values.sortedByDescending { it.connected }
        _peers.value = list
        if (running) {
            _state.update { it.copy(peerCount = list.count { p -> p.connected }) }
        }
    }

    private fun connectedCount(): Int = peerMap.values.count { it.connected }

    companion object {
        const val SERVICE_ID = "com.network.messenger.mesh"
        private const val MAX_PAYLOAD_BYTES = 512 * 1024
    }
}
