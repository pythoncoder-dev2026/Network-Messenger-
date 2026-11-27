package com.network.messenger.transport

import com.network.messenger.domain.model.PeerInfo
import com.network.messenger.domain.model.TransportState
import com.network.messenger.domain.model.TransportType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class IncomingFrame(
    val sourceTransport: TransportType,
    val sourcePeerId: String,
    val payloadBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingFrame) return false
        return sourceTransport == other.sourceTransport &&
            sourcePeerId == other.sourcePeerId &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        var result = sourceTransport.hashCode()
        result = 31 * result + sourcePeerId.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }
}

/**
 * Pluggable transport abstraction so Nearby (BT/BLE + Wi-Fi), Wi-Fi LAN/Direct, and Internet WS
 * are handled identically by TransportManager and MeshRouter.
 */
interface Transport {
    val type: TransportType
    val state: StateFlow<TransportState>
    val peers: StateFlow<List<PeerInfo>>
    val incomingFrames: SharedFlow<IncomingFrame>

    suspend fun start(localNodeId: String, subscribedNetworkIds: Set<String>)
    suspend fun stop()
    suspend fun updateSubscribedNetworks(networkIds: Set<String>)
    suspend fun broadcast(payloadBytes: ByteArray, excludePeerId: String? = null): Int
    suspend fun sendToPeer(peerId: String, payloadBytes: ByteArray): Boolean
}
