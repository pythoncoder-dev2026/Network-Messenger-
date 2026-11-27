package com.network.messenger.data.repository

import android.database.sqlite.SQLiteFullException
import com.network.messenger.data.local.MessageDao
import com.network.messenger.data.local.MessageEntity
import com.network.messenger.data.local.PendingPacketEntity
import com.network.messenger.domain.model.DeliveryState
import com.network.messenger.domain.model.LogCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceClipCache(
    val streamId: String,
    val codec: String,
    val sampleRate: Int,
    val audioBytes: ByteArray
)

data class CachedMessageContent(
    val textBody: String? = null,
    val voiceClip: VoiceClipCache? = null
)

data class ChatMessageItem(
    val packetId: String,
    val networkId: String,
    val senderId: String,
    val type: String,
    val payloadSizeBytes: Int,
    val deliveryState: DeliveryState,
    val hopCount: Int,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val textBody: String? = null,
    val voiceCodec: String? = null,
    val voiceStreamId: String? = null,
    val hasPlayableVoice: Boolean = false
) {
    val isVoice: Boolean
        get() = type == "VOICE_CLIP" || type == "VOICE_LIVE"
}

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val logRepository: LogRepository
) {
    private val inMemoryContent = MutableStateFlow<Map<String, CachedMessageContent>>(emptyMap())

    val pendingQueueCount: Flow<Int> = messageDao.observePendingCount()

    fun observeMessages(networkId: String): Flow<List<MessageEntity>> =
        messageDao.observeMessages(networkId)

    fun observeChatMessages(networkId: String): Flow<List<ChatMessageItem>> =
        combine(messageDao.observeMessages(networkId), inMemoryContent) { entities, cache ->
            entities.asReversed().map { entity -> entity.toChatItem(cache[entity.packetId]) }
        }

    fun observeLatestMessageByNetwork(): Flow<Map<String, ChatMessageItem>> =
        combine(messageDao.observeAllMessages(), inMemoryContent) { entities, cache ->
            val latest = LinkedHashMap<String, ChatMessageItem>()
            entities.forEach { entity ->
                latest[entity.networkId] = entity.toChatItem(cache[entity.packetId])
            }
            latest
        }

    fun getVoiceClip(packetId: String): VoiceClipCache? = inMemoryContent.value[packetId]?.voiceClip

    suspend fun getUnreadIncomingMessages(networkId: String): List<MessageEntity> =
        messageDao.getUnreadIncomingMessages(networkId)

    suspend fun recordMessage(
        packetId: String,
        networkId: String,
        senderId: String,
        type: String,
        payloadSizeBytes: Int,
        deliveryState: DeliveryState,
        hopCount: Int,
        timestampMs: Long,
        isOutgoing: Boolean,
        textBody: String? = null,
        voiceClip: VoiceClipCache? = null
    ) {
        if (textBody != null || voiceClip != null) {
            inMemoryContent.update { current ->
                current + (packetId to CachedMessageContent(textBody = textBody, voiceClip = voiceClip))
            }
        }
        try {
            messageDao.upsertMessage(
                MessageEntity(
                    packetId = packetId,
                    networkId = networkId,
                    senderId = senderId,
                    type = type,
                    payloadSizeBytes = payloadSizeBytes,
                    deliveryState = deliveryState.name,
                    hopCount = hopCount,
                    timestampMs = timestampMs,
                    isOutgoing = isOutgoing
                )
            )
        } catch (e: SQLiteFullException) {
            logRepository.error(LogCategory.ERROR, "Low storage: failed to store message metadata (${e.message})")
        } catch (e: Exception) {
            logRepository.error(LogCategory.ERROR, "Database write error: ${e.message}")
        }
    }

    private fun MessageEntity.toChatItem(cached: CachedMessageContent?): ChatMessageItem {
        val parsedState = runCatching { DeliveryState.valueOf(deliveryState) }
            .getOrDefault(DeliveryState.SENT)
        return ChatMessageItem(
            packetId = packetId,
            networkId = networkId,
            senderId = senderId,
            type = type,
            payloadSizeBytes = payloadSizeBytes,
            deliveryState = parsedState,
            hopCount = hopCount,
            timestampMs = timestampMs,
            isOutgoing = isOutgoing,
            textBody = cached?.textBody,
            voiceCodec = cached?.voiceClip?.codec,
            voiceStreamId = cached?.voiceClip?.streamId,
            hasPlayableVoice = cached?.voiceClip != null
        )
    }

    suspend fun updateDeliveryState(packetId: String, state: DeliveryState, hopCount: Int? = null) {
        try {
            if (hopCount != null) {
                messageDao.updateDeliveryState(packetId, state.name, hopCount)
            } else {
                messageDao.updateDeliveryStateOnly(packetId, state.name)
            }
            logRepository.updatePacketDeliveryState(packetId, state)
        } catch (e: Exception) {
            logRepository.warn(LogCategory.ERROR, "Failed updating delivery state for ${packetId.take(8)}: ${e.message}")
        }
    }

    suspend fun enqueueForStoreAndForward(
        packetId: String,
        networkId: String,
        serializedEnvelope: String,
        expiryHours: Int = DEFAULT_EXPIRY_HOURS
    ): Boolean {
        return try {
            // De-duplicate offline queue by packetId
            if (messageDao.hasPending(packetId)) {
                return false
            }
            val now = System.currentTimeMillis()
            val ttlMs = expiryHours.coerceIn(1, 168) * 60L * 60L * 1000L
            messageDao.enqueuePending(
                PendingPacketEntity(
                    packetId = packetId,
                    networkId = networkId,
                    serializedEnvelope = serializedEnvelope,
                    createdAtMs = now,
                    expiresAtMs = now + ttlMs,
                    attempts = 0,
                    nextRetryAtMs = now + INITIAL_RETRY_BACKOFF_MS,
                    lastError = null
                )
            )
            true
        } catch (e: SQLiteFullException) {
            logRepository.error(
                LogCategory.ERROR,
                "Low storage: could not enqueue offline packet ${packetId.take(8)} (${e.message})"
            )
            false
        } catch (e: Exception) {
            logRepository.error(LogCategory.ERROR, "Offline queue write error: ${e.message}")
            false
        }
    }

    suspend fun purgeAndReturnExpired(): List<PendingPacketEntity> {
        val now = System.currentTimeMillis()
        val expired = messageDao.getExpiredPendingPackets(now)
        if (expired.isNotEmpty()) {
            messageDao.purgeExpiredPending(now)
            expired.forEach { pkt ->
                messageDao.updateDeliveryStateOnly(pkt.packetId, DeliveryState.FAILED.name)
                logRepository.updatePacketDeliveryState(pkt.packetId, DeliveryState.FAILED)
            }
        }
        return expired
    }

    suspend fun getDuePendingPackets(forceAll: Boolean = false): List<PendingPacketEntity> {
        val now = System.currentTimeMillis()
        return if (forceAll) {
            messageDao.getValidPendingPackets(now)
        } else {
            messageDao.getDuePendingPackets(now)
        }
    }

    suspend fun markPendingDispatched(packetId: String) {
        messageDao.removePending(packetId)
    }

    suspend fun scheduleBackoffRetry(packetId: String, currentAttempts: Int, reason: String) {
        val backoffMs = computeBackoffMs(currentAttempts + 1)
        val nextAt = System.currentTimeMillis() + backoffMs
        messageDao.recordPendingRetry(packetId, nextAt, reason)
    }

    companion object {
        const val DEFAULT_EXPIRY_HOURS = 24
        private const val INITIAL_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 5 * 60 * 1000L // 5 minutes

        fun computeBackoffMs(attempt: Int): Long {
            val factor = when {
                attempt <= 1 -> 1L
                attempt == 2 -> 3L
                attempt == 3 -> 9L
                attempt == 4 -> 24L
                else -> 60L
            }
            return (INITIAL_RETRY_BACKOFF_MS * factor).coerceAtMost(MAX_RETRY_BACKOFF_MS)
        }
    }
}
