package com.network.messenger.transport

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LinkQuality
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates all pluggable Transport instances (Nearby BT/BLE, Wi-Fi LAN/Direct, Internet WS),
 * monitors OS radio/connectivity/battery edge cases, and bridges frames uniformly for MeshRouter.
 */
@Singleton
class TransportManager @Inject constructor(
    private val context: Context,
    private val nearbyTransport: NearbyConnectionsTransport,
    private val wifiLanTransport: WifiLanTransport,
    private val webSocketTransport: WebSocketRelayTransport,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) {
    private val plugins: Map<TransportType, Transport> = mapOf(
        TransportType.NEARBY to nearbyTransport,
        TransportType.WIFI_LAN to wifiLanTransport,
        TransportType.INTERNET_WS to webSocketTransport
    )

    private val _transportStates = MutableStateFlow(
        TransportType.entries.associateWith { TransportState(type = it) }
    )
    val transportStates: StateFlow<Map<TransportType, TransportState>> = _transportStates.asStateFlow()

    private val _allPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val allPeers: StateFlow<List<PeerInfo>> = _allPeers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 512)
    val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    private val _peerConnectedSignal = MutableSharedFlow<PeerInfo>(extraBufferCapacity = 32)
    val peerConnectedSignal: SharedFlow<PeerInfo> = _peerConnectedSignal.asSharedFlow()

    private val _lowBattery = MutableStateFlow(false)
    val lowBattery: StateFlow<Boolean> = _lowBattery.asStateFlow()

    private var monitorsRegistered = false
    private var previousConnectedPeerIds: Set<String> = emptySet()

    init {
        // Fan-in incoming frames from every plugin
        plugins.values.forEach { plugin ->
            scope.launch {
                plugin.incomingFrames.collect { frame ->
                    _incomingFrames.emit(frame)
                }
            }
        }

        // Combine transport states
        scope.launch {
            combine(
                nearbyTransport.state,
                wifiLanTransport.state,
                webSocketTransport.state
            ) { nearby, wifi, ws ->
                mapOf(
                    TransportType.NEARBY to nearby,
                    TransportType.WIFI_LAN to wifi,
                    TransportType.INTERNET_WS to ws
                )
            }.collect { combined ->
                _transportStates.value = combined
            }
        }

        // Combine peer lists and emit connection events for store-and-forward flush
        scope.launch {
            combine(
                nearbyTransport.peers,
                wifiLanTransport.peers,
                webSocketTransport.peers
            ) { a, b, c -> a + b + c }.collect { merged ->
                _allPeers.value = merged
                val nowConnected = merged.filter { it.connected }
                val newlyConnected = nowConnected.filter { it.peerId !in previousConnectedPeerIds }
                previousConnectedPeerIds = nowConnected.map { it.peerId }.toSet()
                newlyConnected.forEach { peer ->
                    _peerConnectedSignal.emit(peer)
                }
            }
        }

        registerSystemEdgeCaseMonitors()
    }

    suspend fun setTransportEnabled(
        type: TransportType,
        enabled: Boolean,
        localNodeId: String,
        subscribedNetworks: Set<String>
    ) {
        val transport = plugins[type] ?: return
        if (enabled) {
            logRepository.info(LogCategory.TRANSPORT, "Starting transport ${type.label} (${type.code})")
            transport.start(localNodeId, subscribedNetworks)
        } else {
            transport.stop()
        }
    }

    suspend fun stopAll() {
        plugins.values.forEach { it.stop() }
    }

    suspend fun updateSubscribedNetworks(networkIds: Set<String>) {
        plugins.values.forEach { it.updateSubscribedNetworks(networkIds) }
    }

    fun updateRelayServerUrl(url: String) {
        webSocketTransport.setServerUrl(url)
    }

    /**
     * Broadcasts an encrypted MeshPacket across ALL active transports, optionally excluding
     * the originating transport+peer to prevent routing loops while bridging across worlds
     * (e.g., Bluetooth Nearby <-> Internet WebSocket).
     */
    suspend fun broadcastAcrossAll(
        payloadBytes: ByteArray,
        excludeTransport: TransportType? = null,
        excludePeerId: String? = null
    ): Int {
        var totalSent = 0
        plugins.forEach { (type, plugin) ->
            val skipPeer = if (type == excludeTransport) excludePeerId else null
            totalSent += plugin.broadcast(payloadBytes, excludePeerId = skipPeer)
        }
        return totalSent
    }

    fun hasAnyActiveTransport(): Boolean =
        _transportStates.value.values.any { it.status == TransportStatus.ACTIVE || it.status == TransportStatus.STARTING }

    fun evaluateLinkQuality(): LinkQuality {
        val connected = _allPeers.value.filter { it.connected }
        if (connected.isEmpty()) return LinkQuality.NONE
        if (_lowBattery.value) return LinkQuality.WEAK
        val hasDirectFastLink = connected.any {
            it.transport == TransportType.WIFI_LAN ||
                it.transport == TransportType.NEARBY ||
                (it.transport == TransportType.INTERNET_WS && (it.rssiOrLatencyMs ?: 999) < 250)
        }
        return if (hasDirectFastLink) LinkQuality.GOOD else LinkQuality.WEAK
    }

    private fun registerSystemEdgeCaseMonitors() {
        if (monitorsRegistered) return
        monitorsRegistered = true

        // Bluetooth & Low Battery broadcast monitor
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_OFF -> nearbyTransport.onBluetoothStateChanged(false)
                            BluetoothAdapter.STATE_ON -> nearbyTransport.onBluetoothStateChanged(true)
                        }
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        _lowBattery.value = true
                        logRepository.warn(
                            LogCategory.SYSTEM,
                            "Low battery detected — switching voice PTT to compact store-and-forward clips to save radio power"
                        )
                    }
                    Intent.ACTION_BATTERY_OKAY -> {
                        _lowBattery.value = false
                        logRepository.info(LogCategory.SYSTEM, "Battery level normal — full mesh radio power restored")
                    }
                }
            }
        }
        runCatching { context.registerReceiver(receiver, filter) }

        // ConnectivityManager network loss/restore monitor
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                        webSocketTransport.onConnectivityRestored()
                    }
                }

                override fun onLost(network: Network) {
                    webSocketTransport.onConnectivityLost()
                }
            })
        }
    }
}
