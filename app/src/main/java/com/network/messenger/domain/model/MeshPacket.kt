package com.network.messenger.domain.model

import com.squareup.moshi.JsonClass
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

enum class PacketType {
    TEXT,
    VOICE_LIVE,
    VOICE_CLIP,
    KEY_EXCHANGE_REQ,
    KEY_EXCHANGE_RESP,
    KEY_ROTATE,
    MEMBER_REMOVE,
    DELIVERY_ACK,
    READ_RECEIPT,
    TYPING_INDICATOR,
    USER_REPORT,
    NETWORK_ANNOUNCE
}

/**
 * Signed mesh envelope transported verbatim across Nearby, Wi-Fi LAN/Direct, and Internet WS relay.
 * Relays and the Internet server see only routing metadata and opaque ciphertext.
 */
@JsonClass(generateAdapter = true)
data class MeshPacket(
    val packetId: String,
    val networkId: String,
    val senderId: String,
    val senderEd25519Pub: String,
    val senderX25519Pub: String,
    val targetUserId: String? = null,
    val type: String,
    val timestampMs: Long,
    val ttl: Int,
    val hopCount: Int = 0,
    val keyVersion: Int = 1,
    val encryptedPayload: String,
    val signature: String
) {
    fun packetTypeOrNull(): PacketType? = runCatching { PacketType.valueOf(type) }.getOrNull()

    /**
     * Deterministic canonical bytes covered by the Ed25519 signature.
     * Deliberately excludes mutable routing fields (ttl, hopCount) so relays can forward
     * packets without invalidating the sender's signature.
     */
    fun canonicalSigningBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeUTF("NMSG_V1")
            out.writeUTF(packetId)
            out.writeUTF(networkId)
            out.writeUTF(senderId)
            out.writeUTF(senderEd25519Pub)
            out.writeUTF(senderX25519Pub)
            out.writeUTF(targetUserId.orEmpty())
            out.writeUTF(type)
            out.writeLong(timestampMs)
            out.writeInt(keyVersion)
            out.writeUTF(encryptedPayload)
        }
        return baos.toByteArray()
    }

    /**
     * Associated data bound into AES-256-GCM encryption to prevent ciphertext splicing across
     * networks, senders, packet IDs, or key epochs.
     */
    fun associatedDataBytes(): ByteArray {
        return "$packetId|$networkId|$senderId|$type|$keyVersion".toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MAX_TTL = 5
        const val RECEIPT_MAX_TTL = 3
        const val TYPING_TTL = 2
        // Allows up to 24h store-and-forward + clock drift between offline phones while still
        // rejecting ancient replays; exact packetId deduplication guards against all replays.
        const val REPLAY_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val CLOCK_SKEW_WARN_MS = 5 * 60 * 1000L
    }
}

@JsonClass(generateAdapter = true)
data class TextPayload(
    val body: String,
    val sentAtMs: Long
)

@JsonClass(generateAdapter = true)
data class VoicePayload(
    val streamId: String,
    val seq: Int,
    val isFinal: Boolean,
    val codec: String,
    val sampleRate: Int,
    val bitrateBps: Int,
    val audioBase64: String
)

@JsonClass(generateAdapter = true)
data class KeyExchangeRequestPayload(
    val requesterEd25519Pub: String,
    val requesterX25519Pub: String,
    val passwordProofBase64: String? = null
)

@JsonClass(generateAdapter = true)
data class KeyDistributionPayload(
    val networkName: String,
    val ownerId: String,
    val ownerEd25519Pub: String,
    val ownerX25519Pub: String,
    val keyVersion: Int,
    val symmetricKeyBase64: String,
    val removedUserId: String? = null
)

@JsonClass(generateAdapter = true)
data class DeliveryAckPayload(
    val ackedPacketId: String,
    val receivedAtMs: Long,
    val observedHops: Int,
    val status: String = "DELIVERED"
)

@JsonClass(generateAdapter = true)
data class ReadReceiptPayload(
    val readPacketIds: List<String>,
    val readAtMs: Long
)

@JsonClass(generateAdapter = true)
data class TypingPayload(
    val isTyping: Boolean,
    val sentAtMs: Long
)

@JsonClass(generateAdapter = true)
data class UserReportPayload(
    val reportId: String,
    val reportedUserId: String,
    val reason: String,
    val createdAtMs: Long
)

@JsonClass(generateAdapter = true)
data class NetworkAnnouncePayload(
    val name: String,
    val ownerId: String,
    val ownerEd25519Pub: String,
    val ownerX25519Pub: String,
    val hasPassword: Boolean
)
