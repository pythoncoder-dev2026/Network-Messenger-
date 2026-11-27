package com.network.messenger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkDao {
    @Query("SELECT * FROM mesh_networks ORDER BY createdAtMs DESC")
    fun observeAllNetworks(): Flow<List<MeshNetworkEntity>>

    @Query("SELECT * FROM mesh_networks")
    suspend fun getAllNetworks(): List<MeshNetworkEntity>

    @Query("SELECT * FROM mesh_networks WHERE networkId = :networkId LIMIT 1")
    suspend fun getNetworkById(networkId: String): MeshNetworkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetwork(entity: MeshNetworkEntity)

    @Query("UPDATE mesh_networks SET isMuted = :muted WHERE networkId = :networkId")
    suspend fun setNetworkMuted(networkId: String, muted: Boolean)

    @Query("DELETE FROM mesh_networks WHERE networkId = :networkId")
    suspend fun deleteNetwork(networkId: String)

    @Query("SELECT * FROM network_members WHERE networkId = :networkId ORDER BY joinedAtMs ASC")
    fun observeMembers(networkId: String): Flow<List<NetworkMemberEntity>>

    @Query("SELECT * FROM network_members")
    suspend fun getAllMembers(): List<NetworkMemberEntity>

    @Query("SELECT * FROM network_members WHERE networkId = :networkId")
    suspend fun getMembers(networkId: String): List<NetworkMemberEntity>

    @Query("SELECT * FROM network_members WHERE networkId = :networkId AND userId = :userId LIMIT 1")
    suspend fun getMember(networkId: String, userId: String): NetworkMemberEntity?

    @Query("SELECT * FROM network_members WHERE userId = :userId")
    suspend fun getMembersByUserId(userId: String): List<NetworkMemberEntity>

    @Query("SELECT COUNT(*) FROM network_members WHERE networkId = :networkId")
    suspend fun getMemberCount(networkId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: NetworkMemberEntity)

    @Query("UPDATE network_members SET verified = :verified, verifiedAtMs = :verifiedAtMs, keyChangedWarning = 0 WHERE userId = :userId")
    suspend fun markUserVerified(userId: String, verified: Boolean, verifiedAtMs: Long?)

    @Query("UPDATE network_members SET verified = 0, keyChangedWarning = 1, previousKeyFingerprint = :previousFp, ed25519PubBase64 = :newEdPub, x25519PubBase64 = :newXPub WHERE userId = :userId")
    suspend fun flagKeyChanged(userId: String, previousFp: String, newEdPub: String, newXPub: String)

    @Query("DELETE FROM network_members WHERE networkId = :networkId AND userId = :userId")
    suspend fun deleteMember(networkId: String, userId: String)

    @Query("DELETE FROM network_members WHERE networkId = :networkId")
    suspend fun deleteAllMembers(networkId: String)
}

@Dao
interface BlockedUserDao {
    @Query("SELECT * FROM blocked_users ORDER BY blockedAtMs DESC")
    fun observeBlockedUsers(): Flow<List<BlockedUserEntity>>

    @Query("SELECT * FROM blocked_users")
    suspend fun getAllBlockedUsers(): List<BlockedUserEntity>

    @Query("SELECT userId FROM blocked_users")
    suspend fun getBlockedUserIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_users WHERE userId = :userId)")
    suspend fun isBlocked(userId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun blockUser(entity: BlockedUserEntity)

    @Query("DELETE FROM blocked_users WHERE userId = :userId")
    suspend fun unblockUser(userId: String)
}

@Dao
interface MutedUserDao {
    @Query("SELECT * FROM muted_users ORDER BY mutedAtMs DESC")
    fun observeMutedUsers(): Flow<List<MutedUserEntity>>

    @Query("SELECT * FROM muted_users")
    suspend fun getAllMutedUsers(): List<MutedUserEntity>

    @Query("SELECT userId FROM muted_users")
    suspend fun getMutedUserIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun muteUser(entity: MutedUserEntity)

    @Query("DELETE FROM muted_users WHERE userId = :userId")
    suspend fun unmuteUser(userId: String)
}

@Dao
interface UserReportDao {
    @Query("SELECT * FROM user_reports ORDER BY createdAtMs DESC")
    fun observeReports(): Flow<List<UserReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(entity: UserReportEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE networkId = :networkId ORDER BY timestampMs DESC LIMIT 200")
    fun observeMessages(networkId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE networkId = :networkId AND isOutgoing = 0 AND deliveryState != 'READ' ORDER BY timestampMs ASC")
    suspend fun getUnreadIncomingMessages(networkId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(entity: MessageEntity)

    @Query("UPDATE messages SET deliveryState = :state, hopCount = :hopCount WHERE packetId = :packetId")
    suspend fun updateDeliveryState(packetId: String, state: String, hopCount: Int)

    @Query("UPDATE messages SET deliveryState = :state WHERE packetId = :packetId")
    suspend fun updateDeliveryStateOnly(packetId: String, state: String)

    @Query("SELECT * FROM pending_packets WHERE expiresAtMs > :nowMs AND nextRetryAtMs <= :nowMs ORDER BY createdAtMs ASC")
    suspend fun getDuePendingPackets(nowMs: Long): List<PendingPacketEntity>

    @Query("SELECT * FROM pending_packets WHERE expiresAtMs > :nowMs ORDER BY createdAtMs ASC")
    suspend fun getValidPendingPackets(nowMs: Long): List<PendingPacketEntity>

    @Query("SELECT * FROM pending_packets WHERE expiresAtMs <= :nowMs")
    suspend fun getExpiredPendingPackets(nowMs: Long): List<PendingPacketEntity>

    @Query("SELECT COUNT(*) FROM pending_packets")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_packets WHERE packetId = :packetId)")
    suspend fun hasPending(packetId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueuePending(entity: PendingPacketEntity)

    @Query("DELETE FROM pending_packets WHERE packetId = :packetId")
    suspend fun removePending(packetId: String)

    @Query("DELETE FROM pending_packets WHERE expiresAtMs <= :nowMs")
    suspend fun purgeExpiredPending(nowMs: Long)

    @Query("UPDATE pending_packets SET attempts = attempts + 1, nextRetryAtMs = :nextRetryAtMs, lastError = :lastError WHERE packetId = :packetId")
    suspend fun recordPendingRetry(packetId: String, nextRetryAtMs: Long, lastError: String?)
}
