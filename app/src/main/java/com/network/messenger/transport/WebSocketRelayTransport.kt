package com.network.messenger.transport

import android.util.Base64
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportStatus
import com.network.messenger.domain.model.TransportType
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import kotlin.math.min

@JsonClass(generateAdapter = true)
data class RelayControlFrame(
    val action: String,
    val nodeId: String? = null,
    val networks: List<String>? = null,
    val networkId: String? = null,
    val payloadBase64: String? = null,
    val peersOnline: Int? = null,
    val ts: Long? = null
)

/**
 * Internet WebSocket relay transport connecting to the zero-knowledge Node.js relay server.
 * The server only sees routing metadata (networkId) and signed, encrypted MeshPacket bytes.
 */
class WebSocketRelayTransport(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : Transport {

    override val type: TransportType = TransportType.INTERNET_WS

    private val frameAdapter = moshi.adapter(RelayControlFrame::class.java)

    private val _state = MutableStateFlow(TransportState(type = TransportType.INTERNET_WS))
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 256)
    override val incomingFrames: SharedFlow<IncomingFrame> = _incomingFrames.asSharedFlow()

    @Volatile
    private var serverUrl: String = "ws://10.0.2.2:8080"
    @Volatile
    private var running: Boolean = false
    @Volatile
    private var connected: Boolean = false
    private var localNodeId: String = ""
    private var subscribedNetworks: Set<String> = emptySet()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffMs: Long = INITIAL_BACKOFF_MS

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        if (parsed == null || (parsed.scheme != "ws" && parsed.scheme != "wss") || parsed.host.isNullOrBlank()) {
            logRepository.error(
                LogCategory.TRANSPORT,
                "[WS] Rejected invalid relay URL (must be ws:// or wss://)"
            )
            return
        }
        val changed = serverUrl != trimmed
        serverUrl = trimmed
        if (changed && running) {
            logRepository.info(LogCategory.TRANSPORT, "[WS] Relay URL updated to $trimmed; reconnecting")
            scope.launch {
                stopInternal(logStop = false)
                connectNow()
            }
        }
    }

    override suspend fun start(localNodeId: String, subscribedNetworkIds: Set<String>) {
        if (running) return
        this.localNodeId = localNodeId
        this.subscribedNetworks = subscribedNetworkIds
        running = true
        backoffMs = INITIAL_BACKOFF_MS
        connectNow()
    }

    override suspend fun stop() {
        stopInternal(logStop = true)
    }

    override suspend fun updateSubscribedNetworks(networkIds: Set<String>) {
        subscribedNetworks = networkIds
        if (connected) {
            sendControlFrame(
                RelayControlFrame(
                    action = "subscribe",
                    nodeId = localNodeId,
                    networks = networkIds.toList()
                )
            )
        }
    }

    override suspend fun broadcast(payloadBytes: ByteArray, excludePeerId: String?): Int {
        if (!running || !connected || excludePeerId == RELAY_PEER_ID) return 0
        val encoded = Base64.encodeToString(payloadBytes, Base64.NO_WRAP)
        val ok = sendControlFrame(
            RelayControlFrame(
                action = "relay",
                nodeId = localNodeId,
                payloadBase64 = encoded
            )
        )
        return if (ok) 1 else 0
    }

    override suspend fun sendToPeer(peerId: String, payloadBytes: ByteArray): Boolean {
        if (peerId != RELAY_PEER_ID) return false
        return broadcast(payloadBytes) > 0
    }

    fun onConnectivityRestored() {
        if (running && !connected) {
            logRepository.info(LogCategory.TRANSPORT, "[WS] Internet restored — triggering immediate relay reconnect")
            backoffMs = INITIAL_BACKOFF_MS
            reconnectJob?.cancel()
            connectNow()
        }
    }

    fun onConnectivityLost() {
        if (running) {
            connected = false
            _peers.value = emptyList()
            _state.value = TransportState(
                type = TransportType.INTERNET_WS,
                status = TransportStatus.DEGRADED,
                detail = "No Internet — waiting"
            )
            logRepository.warn(LogCategory.TRANSPORT, "[WS] Internet connectivity lost; packets will queue for store-and-forward")
        }
    }

    private fun connectNow() {
        if (!running) return
        _state.value = TransportState(
            type = TransportType.INTERNET_WS,
            status = TransportStatus.STARTING,
            detail = "Connecting $serverUrl"
        )
        logRepository.info(LogCategory.TRANSPORT, "[WS] Connecting to relay server: $serverUrl")

        val request = try {
            Request.Builder().url(serverUrl).build()
        } catch (e: Exception) {
            _state.value = TransportState(
                type = TransportType.INTERNET_WS,
                status = TransportStatus.ERROR,
                detail = "Bad URL"
            )
            logRepository.error(LogCategory.TRANSPORT, "[WS] Invalid relay URL: ${e.message}")
            return
        }

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                backoffMs = INITIAL_BACKOFF_MS
                _peers.value = listOf(
                    PeerInfo(
                        peerId = RELAY_PEER_ID,
                        displayName = "Relay ($serverUrl)",
                        transport = TransportType.INTERNET_WS,
                        connected = true,
                        rssiOrLatencyMs = 40
                    )
                )
                _state.value = TransportState(
                    type = TransportType.INTERNET_WS,
                    status = TransportStatus.ACTIVE,
                    detail = "Connected",
                    peerCount = 1
                )
                logRepository.success(
                    LogCategory.TRANSPORT,
                    "[WS] Connected to Internet relay ($serverUrl) as uid=${CryptoManager.shortId(localNodeId)}"
                )
                sendControlFrame(
                    RelayControlFrame(
                        action = "register",
                        nodeId = localNodeId,
                        networks = subscribedNetworks.toList()
                    )
                )
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val frame = runCatching { frameAdapter.fromJson(text) }.getOrNull() ?: return
                when (frame.action) {
                    "relay" -> {
                        val b64 = frame.payloadBase64 ?: return
                        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
                        scope.launch {
                            _incomingFrames.emit(
                                IncomingFrame(
                                    sourceTransport = TransportType.INTERNET_WS,
                                    sourcePeerId = RELAY_PEER_ID,
                                    payloadBytes = bytes
                                )
                            )
                        }
                    }
                    "status" -> {
                        val online = frame.peersOnline ?: 0
                        _state.value = TransportState(
                            type = TransportType.INTERNET_WS,
                            status = TransportStatus.ACTIVE,
                            detail = "Connected ($online online)",
                            peerCount = maxOf(1, online)
                        )
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDisconnect("closed ($code: $reason)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect(t.message ?: "connection failure")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        connected = false
        _peers.value = emptyList()
        if (!running) return
        _state.value = TransportState(
            type = TransportType.INTERNET_WS,
            status = TransportStatus.DEGRADED,
            detail = "Reconnecting…"
        )
        logRepository.warn(LogCategory.TRANSPORT, "[WS] Disconnected ($reason); retrying in ${backoffMs / 1000}s")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(backoffMs)
            backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            if (isActive && running && !connected) {
                connectNow()
            }
        }
    }

    private fun sendControlFrame(frame: RelayControlFrame): Boolean {
        val ws = webSocket ?: return false
        val json = frameAdapter.toJson(frame)
        return ws.send(json)
    }

    private fun stopInternal(logStop: Boolean) {
        running = false
        connected = false
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { webSocket?.close(1000, "Transport stopped") }
        webSocket = null
        _peers.value = emptyList()
        _state.value = TransportState(
            type = TransportType.INTERNET_WS,
            status = TransportStatus.STOPPED,
            detail = "Stopped"
        )
        if (logStop) {
            logRepository.info(LogCategory.TRANSPORT, "[WS] Internet WebSocket relay stopped")
        }
    }

    companion object {
        const val RELAY_PEER_ID = "ws-relay-hub"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
