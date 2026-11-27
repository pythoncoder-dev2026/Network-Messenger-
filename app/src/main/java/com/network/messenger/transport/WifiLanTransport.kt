package com.network.messenger.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.network.messenger.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Wi-Fi & Wi-Fi Direct mesh transport using Android NsdManager (mDNS _netmesh._tcp.)
 * paired with length-prefixed TCP socket connections and Wi-Fi Direct peer discovery.
 */
class WifiLanTransport(
    private val context: Context,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : Transport {

    override val type: TransportType = TransportType.WIFI_LAN

    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val wifiManager by lazy { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val wifiP2pManager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }

    private val _state = MutableStateFlow(TransportState(type = TransportType.WIFI_LAN))
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val connections = ConcurrentHashMap<String, PeerSocket>()
    private val peerMap = ConcurrentHashMap<String, PeerInfo>()
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 128)
    override val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var localServiceName: String = ""
    @Volatile
    private var running: Boolean = false

    private data class PeerSocket(
        val peerId: String,
        val socket: Socket,
        val output: DataOutputStream,
        val readJob: Job
    )

    override suspend fun start(localNodeId: String, subscribedNetworkIds: Set<String>) {
        if (running) return
        running = true
        localServiceName = "nmesh-${CryptoManager.shortId(localNodeId)}"
        _state.value = TransportState(
            type = TransportType.WIFI_LAN,
            status = TransportStatus.STARTING,
            detail = "Binding TCP & mDNS"
        )

        withContext(Dispatchers.IO) {
            try {
                multicastLock = wifiManager.createMulticastLock("NetworkMessengerMdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                logRepository.warn(LogCategory.TRANSPORT, "[WIFI] MulticastLock unavailable: ${e.message}")
            }

            try {
                val server = ServerSocket(0)
                serverSocket = server
                val port = server.localPort
                logRepository.success(
                    LogCategory.TRANSPORT,
                    "[WIFI] TCP mesh listener bound on port $port (mDNS $SERVICE_TYPE)"
                )

                acceptJob = scope.launch(Dispatchers.IO) {
                    while (isActive && running) {
                        try {
                            val socket = server.accept()
                            val peerKey = "${socket.inetAddress.hostAddress}:${socket.port}"
                            attachSocket(peerKey, peerKey, socket)
                        } catch (_: Exception) {
                            if (!running) break
                        }
                    }
                }

                registerNsdService(port)
                startNsdDiscovery()
                startWifiDirectDiscoveryIfPermitted()

                _state.value = TransportState(
                    type = TransportType.WIFI_LAN,
                    status = TransportStatus.ACTIVE,
                    detail = "mDNS :$port",
                    peerCount = connectedCount()
                )
            } catch (e: Exception) {
                running = false
                _state.value = TransportState(
                    type = TransportType.WIFI_LAN,
                    status = TransportStatus.ERROR,
                    detail = e.message ?: "Bind error"
                )
                logRepository.error(LogCategory.TRANSPORT, "[WIFI] Failed to start Wi-Fi LAN transport: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        if (!running && _state.value.status == TransportStatus.STOPPED) return
        running = false

        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null

        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null

        connections.values.forEach { conn ->
            conn.readJob.cancel()
            runCatching { conn.socket.close() }
        }
        connections.clear()
        peerMap.clear()
        publishPeers()

        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null

        _state.value = TransportState(
            type = TransportType.WIFI_LAN,
            status = TransportStatus.STOPPED,
            detail = "Stopped"
        )
        logRepository.info(LogCategory.TRANSPORT, "[WIFI] Wi-Fi LAN / Direct transport stopped")
    }

    override suspend fun updateSubscribedNetworks(networkIds: Set<String>) = Unit

    override suspend fun broadcast(payloadBytes: ByteArray, excludePeerId: String?): Int {
        if (!running) return 0
        var sent = 0
        withContext(Dispatchers.IO) {
            connections.values
                .filter { it.peerId != excludePeerId }
                .forEach { conn ->
                    if (writeFrame(conn, payloadBytes)) {
                        sent++
                    }
                }
        }
        return sent
    }

    override suspend fun sendToPeer(peerId: String, payloadBytes: ByteArray): Boolean {
        if (!running) return false
        val conn = connections[peerId] ?: return false
        return withContext(Dispatchers.IO) { writeFrame(conn, payloadBytes) }
    }

    private fun writeFrame(conn: PeerSocket, payloadBytes: ByteArray): Boolean {
        return try {
            synchronized(conn.output) {
                conn.output.writeInt(payloadBytes.size)
                conn.output.write(payloadBytes)
                conn.output.flush()
            }
            true
        } catch (e: Exception) {
            logRepository.warn(LogCategory.PEER, "[WIFI] Peer write failed (${conn.peerId}): ${e.message}")
            disconnectPeer(conn.peerId)
            false
        }
    }

    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = localServiceName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                localServiceName = info.serviceName
                logRepository.info(LogCategory.TRANSPORT, "[WIFI] mDNS registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logRepository.warn(LogCategory.TRANSPORT, "[WIFI] mDNS registration failed (code=$errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                logRepository.info(LogCategory.TRANSPORT, "[WIFI] mDNS peer discovery active ($regType)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == localServiceName) return
                if (!serviceInfo.serviceType.contains("_netmesh")) return
                logRepository.info(LogCategory.PEER, "[WIFI] mDNS peer discovered: ${serviceInfo.serviceName}")

                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        logRepository.warn(LogCategory.PEER, "[WIFI] mDNS resolve failed for ${info.serviceName} (code=$errorCode)")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host ?: return
                        val peerId = info.serviceName
                        if (connections.containsKey(peerId)) return
                        connectToPeer(peerId, host.hostAddress ?: return, info.port)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                logRepository.info(LogCategory.PEER, "[WIFI] mDNS peer lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logRepository.warn(LogCategory.TRANSPORT, "[WIFI] mDNS discovery start failed (code=$errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startWifiDirectDiscoveryIfPermitted() {
        val manager = wifiP2pManager ?: return
        if (!PermissionHelper.hasNearbyPermissions(context)) return
        try {
            p2pChannel = manager.initialize(context, context.mainLooper, null)
            p2pChannel?.let { channel ->
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        logRepository.info(LogCategory.TRANSPORT, "[WIFI] Wi-Fi Direct peer discovery started")
                    }

                    override fun onFailure(reason: Int) {
                        logRepository.warn(LogCategory.TRANSPORT, "[WIFI] Wi-Fi Direct discovery unavailable (reason=$reason); continuing with mDNS LAN")
                    }
                })
            }
        } catch (_: SecurityException) {
            logRepository.warn(LogCategory.PERMISSION, "[WIFI] Wi-Fi Direct skipped (missing permission); mDNS LAN remains active")
        }
    }

    private fun connectToPeer(peerId: String, host: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                attachSocket(peerId, "$peerId ($host:$port)", socket)
            } catch (e: Exception) {
                logRepository.warn(LogCategory.PEER, "[WIFI] TCP connect to $peerId failed: ${e.message}")
            }
        }
    }

    private fun attachSocket(peerId: String, displayName: String, socket: Socket) {
        if (connections.containsKey(peerId)) {
            runCatching { socket.close() }
            return
        }
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && running && !socket.isClosed) {
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_FRAME_BYTES) {
                        logRepository.warn(LogCategory.TRANSPORT, "[WIFI] Invalid frame length=$length from $peerId; closing socket")
                        break
                    }
                    val buf = ByteArray(length)
                    input.readFully(buf)
                    _incomingFrames.emit(
                        IncomingFrame(
                            sourceTransport = TransportType.WIFI_LAN,
                            sourcePeerId = peerId,
                            payloadBytes = buf
                        )
                    )
                }
            } catch (_: Exception) {
                // Disconnected
            } finally {
                disconnectPeer(peerId)
            }
        }

        connections[peerId] = PeerSocket(peerId, socket, output, readJob)
        peerMap[peerId] = PeerInfo(
            peerId = peerId,
            userId = peerId.removePrefix("nmesh-"),
            displayName = displayName,
            transport = TransportType.WIFI_LAN,
            connected = true
        )
        publishPeers()
        logRepository.success(LogCategory.PEER, "[WIFI] Peer connected: $displayName total=${connectedCount()}")
    }

    private fun disconnectPeer(peerId: String) {
        val removed = connections.remove(peerId)
        peerMap.remove(peerId)
        if (removed != null) {
            removed.readJob.cancel()
            runCatching { removed.socket.close() }
            publishPeers()
            logRepository.warn(LogCategory.PEER, "[WIFI] Peer lost: $peerId remaining=${connectedCount()}")
        }
    }

    private fun publishPeers() {
        val list = peerMap.values.toList()
        _peers.value = list
        if (running) {
            _state.update { it.copy(peerCount = list.count { p -> p.connected }) }
        }
    }

    private fun connectedCount(): Int = peerMap.values.count { it.connected }

    companion object {
        private const val SERVICE_TYPE = "_netmesh._tcp."
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val MAX_FRAME_BYTES = 512 * 1024
    }
}
