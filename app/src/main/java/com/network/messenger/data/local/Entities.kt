package com.network.messenger.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_networks")
data class MeshNetworkEntity(
    @PrimaryKey val networkId: String,
    val name: String,
    val role: String,
    val ownerId: String,
    val ownerEd25519PubBase64: String,
    val ownerX25519PubBase64: String,
    val hasPassword: Boolean,
    val passwordSaltBase64: String?,
    val wrappedPasswordHash: String?,
    val wrappedSymmetricKey: String,
    val keyVersion: Int,
    val createdAtMs: Long,
    val isMuted: Boolean = false
)

@Entity(tableName = "network_members", primaryKeys = ["networkId", "userId"])
data class NetworkMemberEntity(
    val networkId: String,
    val userId: String,
    val ed25519PubBase64: String,
    val x25519PubBase64: String,
    val role: String,
    val joinedAtMs: Long,
    val verified: Boolean = false,
    val verifiedAtMs: Long? = null,
    val keyChangedWarning: Boolean = false,
    val previousKeyFingerprint: String? = null
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val packetId: String,
    val networkId: String,
    val senderId: String,
    val type: String,
    val payloadSizeBytes: Int,
    val deliveryState: String,
    val hopCount: Int,
    val timestampMs: Long,
    val isOutgoing: Boolean
)

@Entity(tableName = "blocked_users")
data class BlockedUserEntity(
    @PrimaryKey val userId: String,
    val reason: String,
    val blockedAtMs: Long
)

@Entity(tableName = "muted_users")
data class MutedUserEntity(
    @PrimaryKey val userId: String,
    val mutedAtMs: Long
)

@Entity(tableName = "user_reports")
data class UserReportEntity(
    @PrimaryKey val reportId: String,
    val reportedUserId: String,
    val networkId: String?,
    val reason: String,
    val sentToOwner: Boolean,
    val createdAtMs: Long
)

@Entity(tableName = "pending_packets")
data class PendingPacketEntity(
    @PrimaryKey val packetId: String,
    val networkId: String,
    val serializedEnvelope: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val attempts: Int = 0,
    val nextRetryAtMs: Long = 0L,
    val lastError: String? = null
)
