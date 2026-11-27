package com.network.messenger.domain.model

import com.squareup.moshi.JsonClass

enum class TransportType(val code: String, val label: String) {
    NEARBY("BT/BLE", "Nearby"),
    WIFI_LAN("WIFI", "Wi-Fi"),
    INTERNET_WS("WS", "Internet")
}

enum class TransportStatus {
    STOPPED,
    STARTING,
    ACTIVE,
    DEGRADED,
    ERROR
}

enum class LinkQuality {
    NONE,
    WEAK,
    GOOD
}

enum class NetworkRole {
    OWNER,
    MEMBER
}

enum class DeliveryState {
    QUEUED,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    FAILED
}

enum class VoiceMode {
    LIVE_STREAM,
    VOICE_CLIP
}

data class TransportState(
    val type: TransportType,
    val status: TransportStatus = TransportStatus.STOPPED,
    val detail: String = "Stopped",
    val peerCount: Int = 0
)

data class PeerInfo(
    val peerId: String,
    val userId: String? = null,
    val displayName: String,
    val transport: TransportType,
    val connected: Boolean,
    val rssiOrLatencyMs: Int? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class NetworkSummary(
    val networkId: String,
    val name: String,
    val role: NetworkRole,
    val ownerId: String,
    val hasPassword: Boolean,
    val keyVersion: Int,
    val memberCount: Int,
    val createdAtMs: Long,
    val isMuted: Boolean = false
)

data class NetworkMember(
    val networkId: String,
    val userId: String,
    val ed25519PubBase64: String,
    val x25519PubBase64: String,
    val role: NetworkRole,
    val joinedAtMs: Long,
    val verified: Boolean = false,
    val verifiedAtMs: Long? = null,
    val keyChangedWarning: Boolean = false,
    val previousKeyFingerprint: String? = null,
    val safetyNumber: String = ""
)

data class DiscoveredNetwork(
    val networkId: String,
    val name: String,
    val ownerId: String,
    val ownerEd25519PubBase64: String,
    val ownerX25519PubBase64: String,
    val hasPassword: Boolean,
    val viaTransport: TransportType,
    val discoveredAtMs: Long = System.currentTimeMillis()
)

data class BlockedUser(
    val userId: String,
    val reason: String,
    val blockedAtMs: Long
)

data class MutedUser(
    val userId: String,
    val mutedAtMs: Long
)

data class UserReport(
    val reportId: String,
    val reportedUserId: String,
    val networkId: String?,
    val reason: String,
    val sentToOwner: Boolean,
    val createdAtMs: Long
)

data class TypingPeer(
    val networkId: String,
    val userId: String,
    val expiresAtMs: Long
)

data class KeyChangeAlert(
    val userId: String,
    val networkId: String,
    val previousFingerprint: String,
    val newFingerprint: String,
    val detectedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class MeshInvite(
    val v: Int = 1,
    val networkId: String,
    val name: String,
    val ownerId: String,
    val ownerEd25519Pub: String,
    val ownerX25519Pub: String,
    val hasPassword: Boolean,
    val passwordSalt: String? = null,
    val wrappedKey: String? = null,
    val keyVersion: Int = 1,
    val relayHint: String? = null
)

data class PermissionStatus(
    val name: String,
    val label: String,
    val granted: Boolean,
    val requiredFor: String
)

@JsonClass(generateAdapter = true)
data class EncryptedBackupEnvelope(
    val format: String = "NMSG_BACKUP_V1",
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = 210_000,
    val saltBase64: String,
    val ciphertextBase64: String,
    val createdAtMs: Long
)

@JsonClass(generateAdapter = true)
data class BackupNetworkRecord(
    val networkId: String,
    val name: String,
    val role: String,
    val ownerId: String,
    val ownerEd25519PubBase64: String,
    val ownerX25519PubBase64: String,
    val hasPassword: Boolean,
    val passwordSaltBase64: String?,
    val rawSymmetricKeyBase64: String,
    val keyVersion: Int,
    val createdAtMs: Long,
    val isMuted: Boolean
)

@JsonClass(generateAdapter = true)
data class BackupMemberRecord(
    val networkId: String,
    val userId: String,
    val ed25519PubBase64: String,
    val x25519PubBase64: String,
    val role: String,
    val joinedAtMs: Long,
    val verified: Boolean,
    val verifiedAtMs: Long?
)

@JsonClass(generateAdapter = true)
data class BackupBlockedRecord(
    val userId: String,
    val reason: String,
    val blockedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class BackupMutedRecord(
    val userId: String,
    val mutedAtMs: Long
)

@JsonClass(generateAdapter = true)
data class BackupBundlePayload(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long,
    val ed25519PrivBase64: String,
    val ed25519PubBase64: String,
    val x25519PrivBase64: String,
    val x25519PubBase64: String,
    val networks: List<BackupNetworkRecord>,
    val members: List<BackupMemberRecord>,
    val blockedUsers: List<BackupBlockedRecord>,
    val mutedUsers: List<BackupMutedRecord>
)
