package com.network.messenger.mesh

import com.network.messenger.audio.AudioEngine
import com.network.messenger.audio.EncodedVoiceChunk
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.preferences.SettingsStore
import com.network.messenger.data.repository.KeyRotationResult
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.data.repository.MessageRepository
import com.network.messenger.data.repository.NetworkRepository
import com.network.messenger.data.repository.VoiceClipCache
import com.network.messenger.domain.model.DeliveryAckPayload
import com.network.messenger.domain.model.DeliveryState
import com.network.messenger.domain.model.DiscoveredNetwork
import com.network.messenger.domain.model.KeyDistributionPayload
import com.network.messenger.domain.model.KeyExchangeRequestPayload
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.MeshPacket
import com.network.messenger.domain.model.NetworkAnnouncePayload
import com.network.messenger.domain.model.PacketType
import com.network.messenger.domain.model.ReadReceiptPayload
import com.network.messenger.domain.model.TextPayload
import com.network.messenger.domain.model.TransportType
import com.network.messenger.domain.model.TypingPayload
import com.network.messenger.domain.model.TypingPeer
import com.network.messenger.domain.model.UserReport
import com.network.messenger.domain.model.UserReportPayload
import com.network.messenger.domain.model.VoiceMode
import com.network.messenger.domain.model.VoicePayload
import com.network.messenger.transport.IncomingFrame
import com.network.messenger.transport.TransportManager
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Transport-agnostic multi-hop encrypted mesh router.
 * Enforces Ed25519 signature checks, replay/duplicate dropping, local block list dropping,
 * AES-256-GCM E2E encryption/decryption, X25519+HKDF key exchange/rotation, TTL=5 multi-hop
 * bridging across Nearby/Wi-Fi/Internet transports, delivery/read receipts, rate-limited typing
 * indicators, encrypted owner reports, and Room-backed offline queue with retry/backoff & expiry.
 */
@Singleton
class MeshRouter @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val transportManager: TransportManager,
    private val networkRepository: NetworkRepository,
    private val messageRepository: MessageRepository,
    private val audioEngine: AudioEngine,
    private val settingsStore: SettingsStore,
    private val logRepository: LogRepository,
    private val moshi: Moshi,
    private val scope: CoroutineScope
) {
    private val packetAdapter = moshi.adapter(MeshPacket::class.java)
    private val textAdapter = moshi.adapter(TextPayload::class.java)
    private val voiceAdapter = moshi.adapter(VoicePayload::class.java)
    private val keyReqAdapter = moshi.adapter(KeyExchangeRequestPayload::class.java)
    private val keyDistAdapter = moshi.adapter(KeyDistributionPayload::class.java)
    private val ackAdapter = moshi.adapter(DeliveryAckPayload::class.java)
    private val readReceiptAdapter = moshi.adapter(ReadReceiptPayload::class.java)
    private val typingAdapter = moshi.adapter(TypingPayload::class.java)
    private val reportAdapter = moshi.adapter(UserReportPayload::class.java)
    private val announceAdapter = moshi.adapter(NetworkAnnouncePayload::class.java)

    // Bounded LRU map for replay & duplicate packet ID filtering
    private val seenPacketIds: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > MAX_SEEN_PACKETS
            }
        }
    )

    private val _typingPeers = MutableStateFlow<List<TypingPeer>>(emptyList())
    val typingPeers: StateFlow<List<TypingPeer>> = _typingPeers.asStateFlow()

    private val lastTypingSentByNetwork = ConcurrentHashMap<String, Long>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true

        val identity = cryptoManager.getOrCreateIdentity()
        logRepository.setIdentityTag(identity.shortUserId)
        logRepository.info(
            LogCategory.CRYPTO,
            "Identity ready (Android Keystore): uid=${identity.shortUserId} (Ed25519 + X25519)"
        )

        scope.launch {
            networkRepository.warmCaches()
        }

        // Collect all incoming frames from all active transports
        scope.launch {
            transportManager.incomingFrames.collect { frame ->
                handleIncomingFrame(frame)
            }
        }

        // Flush offline queue + re-announce owned networks whenever any peer connects
        scope.launch {
            transportManager.peerConnectedSignal.collect { peer ->
                logRepository.info(
                    LogCategory.PEER,
                    "Link ready [${peer.transport.code}] ${peer.displayName} — flushing offline queue"
                )
                flushStoreAndForward(forceAll = true)
                broadcastOwnedNetworkAnnouncements()
            }
        }

        // Periodic retry/backoff & expiry maintenance worker + typing indicator timeout cleaner
        scope.launch {
            while (isActive) {
                delay(QUEUE_MAINTENANCE_INTERVAL_MS)
                pruneExpiredTyping()
                flushStoreAndForward(forceAll = false)
            }
        }
    }

    suspend fun sendTextMessage(networkId: String, plaintext: String) {
        if (plaintext.isBlank()) return
        val netEntity = networkRepository.getNetworkEntity(networkId)
        val groupKey = networkRepository.getNetworkSymmetricKey(networkId)
        if (netEntity == null || groupKey == null) {
            logRepository.error(
                LogCategory.CRYPTO,
                "Cannot send TEXT on net=${CryptoManager.shortId(networkId)}: missing symmetric group key"
            )
            return
        }

        val identity = cryptoManager.getOrCreateIdentity()
        val packetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val payloadJson = textAdapter.toJson(TextPayload(body = plaintext, sentAtMs = now))
        val plaintextBytes = payloadJson.toByteArray(Charsets.UTF_8)

        val packet = buildSignedGroupPacket(
            packetId = packetId,
            networkId = networkId,
            type = PacketType.TEXT,
            keyVersion = netEntity.keyVersion,
            groupKey = groupKey,
            plaintextBytes = plaintextBytes,
            nowMs = now
        )

        seenPacketIds[packetId] = now
        val wireBytes = packetAdapter.toJson(packet).toByteArray(Charsets.UTF_8)
        val sentCount = transportManager.broadcastAcrossAll(wireBytes)
        val state = if (sentCount > 0) DeliveryState.SENT else DeliveryState.QUEUED

        messageRepository.recordMessage(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            type = PacketType.TEXT.name,
            payloadSizeBytes = wireBytes.size,
            deliveryState = state,
            hopCount = 0,
            timestampMs = now,
            isOutgoing = true,
            textBody = plaintext
        )

        if (sentCount > 0) {
            // Privacy rule: never log plaintext content or keys — only metadata + live receipt ticks
            logRepository.success(
                LogCategory.MESSAGE,
                "Sent TEXT id=${shortPid(packetId)} net=${CryptoManager.shortId(networkId)} from=${identity.shortUserId} size=${wireBytes.size}B keyVer=${netEntity.keyVersion} links=$sentCount [sent]",
                packetId = packetId,
                deliveryState = DeliveryState.SENT
            )
        } else {
            val expiryHours = settingsStore.queueExpiryHours.first()
            messageRepository.enqueueForStoreAndForward(
                packetId = packetId,
                networkId = networkId,
                serializedEnvelope = String(wireBytes, Charsets.UTF_8),
                expiryHours = expiryHours
            )
            logRepository.warn(
                LogCategory.MESSAGE,
                "Queued TEXT id=${shortPid(packetId)} net=${CryptoManager.shortId(networkId)} size=${wireBytes.size}B expiry=${expiryHours}h (offline queue) [queued]",
                packetId = packetId,
                deliveryState = DeliveryState.QUEUED
            )
        }
    }

    /**
     * Sends a low-bandwidth, rate-limited encrypted typing indicator (TTL=2, never queued offline).
     */
    suspend fun sendTypingIndicator(networkId: String, isTyping: Boolean) {
        if (transportManager.allPeers.value.none { it.connected }) return
        val now = System.currentTimeMillis()
        if (isTyping) {
            val lastSent = lastTypingSentByNetwork[networkId] ?: 0L
            if (now - lastSent < TYPING_RATE_LIMIT_MS) return
            lastTypingSentByNetwork[networkId] = now
        } else {
            lastTypingSentByNetwork.remove(networkId)
        }

        val netEntity = networkRepository.getNetworkEntity(networkId) ?: return
        val groupKey = networkRepository.getNetworkSymmetricKey(networkId) ?: return
        val packetId = UUID.randomUUID().toString()
        val payloadBytes = typingAdapter.toJson(TypingPayload(isTyping = isTyping, sentAtMs = now))
            .toByteArray(Charsets.UTF_8)

        val packet = buildSignedGroupPacket(
            packetId = packetId,
            networkId = networkId,
            type = PacketType.TYPING_INDICATOR,
            keyVersion = netEntity.keyVersion,
            groupKey = groupKey,
            plaintextBytes = payloadBytes,
            nowMs = now,
            ttl = MeshPacket.TYPING_TTL
        )
        seenPacketIds[packetId] = now
        val wireBytes = packetAdapter.toJson(packet).toByteArray(Charsets.UTF_8)
        val sent = transportManager.broadcastAcrossAll(wireBytes)
        if (sent > 0) {
            logRepository.info(
                LogCategory.MESSAGE,
                "Typing indicator sent: net=${CryptoManager.shortId(networkId)} active=$isTyping size=${wireBytes.size}B ttl=${MeshPacket.TYPING_TTL}"
            )
        }
    }

    /**
     * Marks unread incoming messages on the active network as READ and sends compact encrypted
     * READ_RECEIPT packets targeted at each sender (capped TTL, never relayed past target).
     */
    suspend fun markNetworkMessagesRead(networkId: String) {
        val unread = messageRepository.getUnreadIncomingMessages(networkId)
        if (unread.isEmpty()) return
        val netEntity = networkRepository.getNetworkEntity(networkId) ?: return
        val groupKey = networkRepository.getNetworkSymmetricKey(networkId) ?: return

        val bySender = unread.groupBy { it.senderId }
        val now = System.currentTimeMillis()

        bySender.forEach { (senderId, msgs) ->
            val ids = msgs.map { it.packetId }
            ids.forEach { pid -> messageRepository.updateDeliveryState(pid, DeliveryState.READ) }

            val receiptId = UUID.randomUUID().toString()
            val payloadBytes = readReceiptAdapter.toJson(
                ReadReceiptPayload(readPacketIds = ids, readAtMs = now)
            ).toByteArray(Charsets.UTF_8)

            val packet = buildSignedGroupPacket(
                packetId = receiptId,
                networkId = networkId,
                type = PacketType.READ_RECEIPT,
                keyVersion = netEntity.keyVersion,
                groupKey = groupKey,
                plaintextBytes = payloadBytes,
                nowMs = now,
                targetUserId = senderId,
                ttl = MeshPacket.RECEIPT_MAX_TTL
            )
            seenPacketIds[receiptId] = now
            val wireBytes = packetAdapter.toJson(packet).toByteArray(Charsets.UTF_8)
            val sent = transportManager.broadcastAcrossAll(wireBytes)
            if (sent > 0) {
                logRepository.info(
                    LogCategory.MESSAGE,
                    "Sent READ_RECEIPT for ${ids.size} msg(s) to=${CryptoManager.shortId(senderId)} net=${CryptoManager.shortId(networkId)} size=${wireBytes.size}B [read]"
                )
            }
        }
    }

    /**
     * Encrypts and sends a user abuse report directly to the network owner via X25519+HKDF+AES-256-GCM.
     */
    suspend fun sendEncryptedReportToOwner(report: UserReport, networkId: String) {
        val netEntity = networkRepository.getNetworkEntity(networkId) ?: return
        val identity = cryptoManager.getOrCreateIdentity()
        if (netEntity.ownerId == identity.userId) return
        val ownerXPub = runCatching { CryptoManager.decodeB64(netEntity.ownerX25519PubBase64) }.getOrNull() ?: return

        val packetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val payload = UserReportPayload(
            reportId = report.reportId,
            reportedUserId = report.reportedUserId,
            reason = report.reason,
            createdAtMs = report.createdAtMs
        )
        val aad = "$packetId|$networkId|${identity.userId}|${PacketType.USER_REPORT.name}|${netEntity.keyVersion}".toByteArray(Charsets.UTF_8)
        val ciphertext = cryptoManager.encryptPairwise(
            peerX25519PublicKey = ownerXPub,
            networkId = networkId,
            plaintext = reportAdapter.toJson(payload).toByteArray(Charsets.UTF_8),
            aad = aad
        )
        val unsigned = MeshPacket(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            senderEd25519Pub = identity.ed25519PublicBase64,
            senderX25519Pub = identity.x25519PublicBase64,
            targetUserId = netEntity.ownerId,
            type = PacketType.USER_REPORT.name,
            timestampMs = now,
            ttl = MeshPacket.MAX_TTL,
            hopCount = 0,
            keyVersion = netEntity.keyVersion,
            encryptedPayload = CryptoManager.encodeB64(ciphertext),
            signature = ""
        )
        val signed = unsigned.copy(
            signature = CryptoManager.encodeB64(cryptoManager.sign(unsigned.canonicalSigningBytes()))
        )
        seenPacketIds[packetId] = now
        val wireJson = packetAdapter.toJson(signed)
        val sent = transportManager.broadcastAcrossAll(wireJson.toByteArray(Charsets.UTF_8))
        if (sent == 0) {
            val expiryHours = settingsStore.queueExpiryHours.first()
            messageRepository.enqueueForStoreAndForward(packetId, networkId, wireJson, expiryHours)
        }
        logRepository.info(
            LogCategory.BLOCK,
            "Encrypted report dispatched to owner=${CryptoManager.shortId(netEntity.ownerId)} net=${CryptoManager.shortId(networkId)} target=${CryptoManager.shortId(report.reportedUserId)} links=$sent"
        )
    }

    suspend fun sendVoiceChunk(networkId: String, chunk: EncodedVoiceChunk, mode: VoiceMode) {
        val netEntity = networkRepository.getNetworkEntity(networkId)
        val groupKey = networkRepository.getNetworkSymmetricKey(networkId)
        if (netEntity == null || groupKey == null) {
            logRepository.error(
                LogCategory.VOICE,
                "Cannot send voice on net=${CryptoManager.shortId(networkId)}: missing group key"
            )
            return
        }

        val identity = cryptoManager.getOrCreateIdentity()
        val packetType = if (mode == VoiceMode.LIVE_STREAM) PacketType.VOICE_LIVE else PacketType.VOICE_CLIP
        val packetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val voicePayload = VoicePayload(
            streamId = chunk.streamId,
            seq = chunk.seq,
            isFinal = chunk.isFinal,
            codec = chunk.codec,
            sampleRate = chunk.sampleRate,
            bitrateBps = chunk.bitrateBps,
            audioBase64 = CryptoManager.encodeB64(chunk.bytes)
        )
        val plaintextBytes = voiceAdapter.toJson(voicePayload).toByteArray(Charsets.UTF_8)

        val packet = buildSignedGroupPacket(
            packetId = packetId,
            networkId = networkId,
            type = packetType,
            keyVersion = netEntity.keyVersion,
            groupKey = groupKey,
            plaintextBytes = plaintextBytes,
            nowMs = now
        )

        seenPacketIds[packetId] = now
        val wireBytes = packetAdapter.toJson(packet).toByteArray(Charsets.UTF_8)
        val sentCount = transportManager.broadcastAcrossAll(wireBytes)
        val state = if (sentCount > 0) DeliveryState.SENT else DeliveryState.QUEUED

        messageRepository.recordMessage(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            type = packetType.name,
            payloadSizeBytes = wireBytes.size,
            deliveryState = state,
            hopCount = 0,
            timestampMs = now,
            isOutgoing = true,
            voiceClip = VoiceClipCache(
                streamId = chunk.streamId,
                codec = chunk.codec,
                sampleRate = chunk.sampleRate,
                audioBytes = chunk.bytes
            )
        )

        if (sentCount > 0) {
            logRepository.success(
                LogCategory.VOICE,
                "Sent ${packetType.name} stream=${chunk.streamId}#${chunk.seq} net=${CryptoManager.shortId(networkId)} codec=${chunk.codec} size=${wireBytes.size}B links=$sentCount [sent]",
                packetId = packetId,
                deliveryState = DeliveryState.SENT
            )
        } else if (packetType == PacketType.VOICE_CLIP) {
            val expiryHours = settingsStore.queueExpiryHours.first()
            messageRepository.enqueueForStoreAndForward(
                packetId = packetId,
                networkId = networkId,
                serializedEnvelope = String(wireBytes, Charsets.UTF_8),
                expiryHours = expiryHours
            )
            logRepository.warn(
                LogCategory.VOICE,
                "Queued VOICE_CLIP stream=${chunk.streamId} net=${CryptoManager.shortId(networkId)} size=${wireBytes.size}B expiry=${expiryHours}h [queued]",
                packetId = packetId,
                deliveryState = DeliveryState.QUEUED
            )
        } else {
            messageRepository.updateDeliveryState(packetId, DeliveryState.FAILED, 0)
            logRepository.warn(
                LogCategory.VOICE,
                "Dropped live voice frame stream=${chunk.streamId}#${chunk.seq} (link lost) [failed]",
                packetId = packetId,
                deliveryState = DeliveryState.FAILED
            )
        }
    }

    suspend fun broadcastNetworkAnnounce(networkId: String) {
        val netEntity = networkRepository.getNetworkEntity(networkId) ?: return
        val identity = cryptoManager.getOrCreateIdentity()
        val now = System.currentTimeMillis()
        val packetId = UUID.randomUUID().toString()
        val announce = NetworkAnnouncePayload(
            name = netEntity.name,
            ownerId = netEntity.ownerId,
            ownerEd25519Pub = netEntity.ownerEd25519PubBase64,
            ownerX25519Pub = netEntity.ownerX25519PubBase64,
            hasPassword = netEntity.hasPassword
        )
        val payloadB64 = CryptoManager.encodeB64(announceAdapter.toJson(announce).toByteArray(Charsets.UTF_8))
        val unsigned = MeshPacket(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            senderEd25519Pub = identity.ed25519PublicBase64,
            senderX25519Pub = identity.x25519PublicBase64,
            type = PacketType.NETWORK_ANNOUNCE.name,
            timestampMs = now,
            ttl = MeshPacket.MAX_TTL,
            hopCount = 0,
            keyVersion = netEntity.keyVersion,
            encryptedPayload = payloadB64,
            signature = ""
        )
        val signed = unsigned.copy(signature = CryptoManager.encodeB64(cryptoManager.sign(unsigned.canonicalSigningBytes())))
        seenPacketIds[packetId] = now
        val wireBytes = packetAdapter.toJson(signed).toByteArray(Charsets.UTF_8)
        transportManager.broadcastAcrossAll(wireBytes)
    }

    suspend fun sendKeyExchangeRequest(
        networkId: String,
        ownerId: String,
        ownerX25519PubBase64: String,
        passwordProofBase64: String? = null
    ) {
        val identity = cryptoManager.getOrCreateIdentity()
        val ownerXPub = runCatching { CryptoManager.decodeB64(ownerX25519PubBase64) }.getOrNull() ?: return
        val packetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val req = KeyExchangeRequestPayload(
            requesterEd25519Pub = identity.ed25519PublicBase64,
            requesterX25519Pub = identity.x25519PublicBase64,
            passwordProofBase64 = passwordProofBase64
        )
        val aad = "$packetId|$networkId|${identity.userId}|${PacketType.KEY_EXCHANGE_REQ.name}|0".toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encryptPairwise(
            peerX25519PublicKey = ownerXPub,
            networkId = networkId,
            plaintext = keyReqAdapter.toJson(req).toByteArray(Charsets.UTF_8),
            aad = aad
        )
        val unsigned = MeshPacket(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            senderEd25519Pub = identity.ed25519PublicBase64,
            senderX25519Pub = identity.x25519PublicBase64,
            targetUserId = ownerId,
            type = PacketType.KEY_EXCHANGE_REQ.name,
            timestampMs = now,
            ttl = MeshPacket.MAX_TTL,
            hopCount = 0,
            keyVersion = 0,
            encryptedPayload = CryptoManager.encodeB64(encrypted),
            signature = ""
        )
        val signed = unsigned.copy(signature = CryptoManager.encodeB64(cryptoManager.sign(unsigned.canonicalSigningBytes())))
        seenPacketIds[packetId] = now
        val wire = packetAdapter.toJson(signed)
        val sent = transportManager.broadcastAcrossAll(wire.toByteArray(Charsets.UTF_8))
        if (sent == 0) {
            val expiryHours = settingsStore.queueExpiryHours.first()
            messageRepository.enqueueForStoreAndForward(packetId, networkId, wire, expiryHours)
        }
        logRepository.info(
            LogCategory.CRYPTO,
            "X25519+HKDF key exchange requested for net=${CryptoManager.shortId(networkId)} to owner=${CryptoManager.shortId(ownerId)}"
        )
    }

    suspend fun distributeKeyRotation(rotation: KeyRotationResult) {
        val identity = cryptoManager.getOrCreateIdentity()
        rotation.remainingMembers.forEach { member ->
            sendPairwiseKeyDistribution(
                networkId = rotation.networkId,
                networkName = rotation.networkName,
                targetUserId = member.userId,
                targetX25519PubBase64 = member.x25519PubBase64,
                keyVersion = rotation.newKeyVersion,
                symmetricKey = rotation.newSymmetricKey,
                packetType = PacketType.KEY_ROTATE
            )
        }
        logRepository.success(
            LogCategory.CRYPTO,
            "Dispatched rotated keyVer=${rotation.newKeyVersion} via X25519+HKDF to ${rotation.remainingMembers.size} member(s) from owner=${identity.shortUserId}"
        )
    }

    suspend fun handleIncomingFrame(frame: IncomingFrame) {
        val json = runCatching { String(frame.payloadBytes, Charsets.UTF_8) }.getOrNull() ?: return
        val packet = runCatching { packetAdapter.fromJson(json) }.getOrNull() ?: return
        val packetType = packet.packetTypeOrNull() ?: return
        val identity = cryptoManager.getOrCreateIdentity()

        // 1. Block list enforcement — drop immediately, NEVER relay, NEVER play voice
        if (networkRepository.isUserBlockedFast(packet.senderId) || networkRepository.isUserBlocked(packet.senderId)) {
            logRepository.warn(
                LogCategory.BLOCK,
                "Dropped ${packet.type} id=${shortPid(packet.packetId)} from blocked uid=${CryptoManager.shortId(packet.senderId)} (reason=blocked)"
            )
            return
        }

        // 2. Duplicate / replay protection with clock-skew awareness
        if (seenPacketIds.containsKey(packet.packetId)) {
            logRepository.info(
                LogCategory.MESSAGE,
                "Dropped ${packet.type} id=${shortPid(packet.packetId)} from=${CryptoManager.shortId(packet.senderId)} (reason=duplicate)"
            )
            return
        }
        val now = System.currentTimeMillis()
        val driftMs = abs(now - packet.timestampMs)
        if (driftMs > MeshPacket.REPLAY_WINDOW_MS) {
            logRepository.warn(
                LogCategory.CRYPTO,
                "Dropped ${packet.type} id=${shortPid(packet.packetId)} from=${CryptoManager.shortId(packet.senderId)} (reason=replay_window, drift=${driftMs / 60000}m)"
            )
            return
        }
        if (driftMs > MeshPacket.CLOCK_SKEW_WARN_MS) {
            logRepository.warn(
                LogCategory.CRYPTO,
                "Clock skew detected (${driftMs / 60000}m) on packet id=${shortPid(packet.packetId)} from=${CryptoManager.shortId(packet.senderId)}"
            )
        }

        // 3. Ed25519 signature & sender identity binding verification
        val edPub = runCatching { CryptoManager.decodeB64(packet.senderEd25519Pub) }.getOrNull()
        val xPub = runCatching { CryptoManager.decodeB64(packet.senderX25519Pub) }.getOrNull()
        val sig = runCatching { CryptoManager.decodeB64(packet.signature) }.getOrNull()
        if (edPub == null || xPub == null || sig == null ||
            !cryptoManager.verifySenderMatchesKeys(packet.senderId, edPub, xPub) ||
            !cryptoManager.verify(edPub, sig, packet.canonicalSigningBytes())
        ) {
            logRepository.error(
                LogCategory.CRYPTO,
                "Dropped ${packet.type} id=${shortPid(packet.packetId)} from=${CryptoManager.shortId(packet.senderId)} (reason=bad_signature)"
            )
            return
        }

        // Mark seen after cryptographic validation so forged IDs cannot poison the cache
        seenPacketIds[packet.packetId] = now

        // 4. Local delivery / protocol handling
        if (packet.senderId != identity.userId) {
            processLocallyIfRelevant(packet, packetType, frame.sourceTransport, frame.payloadBytes.size)
        }

        // 5. Multi-hop relay & cross-transport bridging — stop relaying targeted receipts/reports once delivered to target!
        if (packet.senderId != identity.userId) {
            if (packet.targetUserId != null && packet.targetUserId == identity.userId) {
                return
            }
            relayIfEligible(packet, packetType, frame)
        }
    }

    private suspend fun processLocallyIfRelevant(
        packet: MeshPacket,
        packetType: PacketType,
        viaTransport: TransportType,
        wireSize: Int
    ) {
        val identity = cryptoManager.getOrCreateIdentity()
        val isMuted = networkRepository.isUserMutedFast(packet.senderId) ||
            networkRepository.isNetworkMutedFast(packet.networkId)
        val muteSuffix = if (isMuted) " [muted]" else ""

        when (packetType) {
            PacketType.NETWORK_ANNOUNCE -> {
                val decoded = runCatching {
                    val raw = CryptoManager.decodeB64(packet.encryptedPayload)
                    announceAdapter.fromJson(String(raw, Charsets.UTF_8))
                }.getOrNull() ?: return
                networkRepository.recordDiscoveredNetwork(
                    DiscoveredNetwork(
                        networkId = packet.networkId,
                        name = decoded.name,
                        ownerId = decoded.ownerId,
                        ownerEd25519PubBase64 = decoded.ownerEd25519Pub,
                        ownerX25519PubBase64 = decoded.ownerX25519Pub,
                        hasPassword = decoded.hasPassword,
                        viaTransport = viaTransport
                    )
                )
                logRepository.info(
                    LogCategory.NETWORK,
                    "Discovered network \"${decoded.name}\" id=${CryptoManager.shortId(packet.networkId)} via ${viaTransport.code} (hops=${packet.hopCount})"
                )
            }

            PacketType.KEY_EXCHANGE_REQ -> {
                if (packet.targetUserId != identity.userId) return
                val net = networkRepository.getNetworkEntity(packet.networkId) ?: return
                if (net.ownerId != identity.userId) return
                val peerXPub = runCatching { CryptoManager.decodeB64(packet.senderX25519Pub) }.getOrNull() ?: return
                val reqPayload = runCatching {
                    val plain = cryptoManager.decryptPairwise(
                        peerX25519PublicKey = peerXPub,
                        networkId = packet.networkId,
                        ciphertext = CryptoManager.decodeB64(packet.encryptedPayload),
                        aad = packet.associatedDataBytes()
                    )
                    keyReqAdapter.fromJson(String(plain, Charsets.UTF_8))
                }.getOrNull()

                if (reqPayload == null) {
                    logRepository.error(
                        LogCategory.CRYPTO,
                        "Key exchange failed for uid=${CryptoManager.shortId(packet.senderId)} (reason=decrypt_failed)"
                    )
                    return
                }

                val pwdOk = networkRepository.verifyPasswordProof(packet.networkId, reqPayload.passwordProofBase64)
                if (!pwdOk) {
                    logRepository.error(
                        LogCategory.CRYPTO,
                        "Key exchange rejected for uid=${CryptoManager.shortId(packet.senderId)} (invalid network password)"
                    )
                    return
                }

                val groupKey = networkRepository.getNetworkSymmetricKey(packet.networkId) ?: return
                networkRepository.registerMember(
                    networkId = packet.networkId,
                    userId = packet.senderId,
                    ed25519PubBase64 = reqPayload.requesterEd25519Pub,
                    x25519PubBase64 = reqPayload.requesterX25519Pub
                )
                sendPairwiseKeyDistribution(
                    networkId = net.networkId,
                    networkName = net.name,
                    targetUserId = packet.senderId,
                    targetX25519PubBase64 = reqPayload.requesterX25519Pub,
                    keyVersion = net.keyVersion,
                    symmetricKey = groupKey,
                    packetType = PacketType.KEY_EXCHANGE_RESP
                )
                logRepository.success(
                    LogCategory.CRYPTO,
                    "Key exchange done (X25519+HKDF): provisioned net=${CryptoManager.shortId(net.networkId)} keyVer=${net.keyVersion} to uid=${CryptoManager.shortId(packet.senderId)}"
                )
            }

            PacketType.KEY_EXCHANGE_RESP, PacketType.KEY_ROTATE -> {
                if (packet.targetUserId != identity.userId) return
                val peerXPub = runCatching { CryptoManager.decodeB64(packet.senderX25519Pub) }.getOrNull() ?: return
                val dist = runCatching {
                    val plain = cryptoManager.decryptPairwise(
                        peerX25519PublicKey = peerXPub,
                        networkId = packet.networkId,
                        ciphertext = CryptoManager.decodeB64(packet.encryptedPayload),
                        aad = packet.associatedDataBytes()
                    )
                    keyDistAdapter.fromJson(String(plain, Charsets.UTF_8))
                }.getOrNull()

                if (dist == null) {
                    logRepository.error(
                        LogCategory.CRYPTO,
                        "Key exchange/rotation failed from uid=${CryptoManager.shortId(packet.senderId)} (reason=decrypt_failed)"
                    )
                    return
                }

                val symKey = runCatching { CryptoManager.decodeB64(dist.symmetricKeyBase64) }.getOrNull() ?: return
                networkRepository.applyReceivedNetworkKey(
                    networkId = packet.networkId,
                    networkName = dist.networkName,
                    ownerId = dist.ownerId,
                    ownerEd25519Pub = dist.ownerEd25519Pub,
                    ownerX25519Pub = dist.ownerX25519Pub,
                    keyVersion = dist.keyVersion,
                    symmetricKey = symKey
                )
                logRepository.success(
                    LogCategory.CRYPTO,
                    "${if (packetType == PacketType.KEY_ROTATE) "Key rotation applied" else "Key exchange done"}: net=${CryptoManager.shortId(packet.networkId)} keyVer=${dist.keyVersion} via X25519+HKDF"
                )
            }

            PacketType.USER_REPORT -> {
                if (packet.targetUserId != identity.userId) return
                val net = networkRepository.getNetworkEntity(packet.networkId) ?: return
                if (net.ownerId != identity.userId) return
                val peerXPub = runCatching { CryptoManager.decodeB64(packet.senderX25519Pub) }.getOrNull() ?: return
                val reportPayload = runCatching {
                    val plain = cryptoManager.decryptPairwise(
                        peerX25519PublicKey = peerXPub,
                        networkId = packet.networkId,
                        ciphertext = CryptoManager.decodeB64(packet.encryptedPayload),
                        aad = packet.associatedDataBytes()
                    )
                    reportAdapter.fromJson(String(plain, Charsets.UTF_8))
                }.getOrNull() ?: return

                networkRepository.storeIncomingOwnerReport(
                    reportId = reportPayload.reportId,
                    reportedUserId = reportPayload.reportedUserId,
                    networkId = packet.networkId,
                    reason = reportPayload.reason,
                    createdAtMs = reportPayload.createdAtMs
                )
                logRepository.warn(
                    LogCategory.BLOCK,
                    "Owner received encrypted report: net=${CryptoManager.shortId(packet.networkId)} reporter=${CryptoManager.shortId(packet.senderId)} target=${CryptoManager.shortId(reportPayload.reportedUserId)}"
                )
            }

            PacketType.TEXT,
            PacketType.VOICE_LIVE,
            PacketType.VOICE_CLIP,
            PacketType.DELIVERY_ACK,
            PacketType.READ_RECEIPT,
            PacketType.TYPING_INDICATOR -> {
                if (packet.targetUserId != null && packet.targetUserId != identity.userId) return
                val netEntity = networkRepository.getNetworkEntity(packet.networkId) ?: return
                val groupKey = networkRepository.getNetworkSymmetricKey(packet.networkId)
                if (groupKey == null) {
                    logRepository.warn(
                        LogCategory.CRYPTO,
                        "Dropped ${packet.type} id=${shortPid(packet.packetId)} (reason=decrypt_failed, no local key)"
                    )
                    return
                }

                val decrypted = runCatching {
                    cryptoManager.decryptGroupPayload(
                        key = groupKey,
                        ciphertext = CryptoManager.decodeB64(packet.encryptedPayload),
                        aad = packet.associatedDataBytes()
                    )
                }.getOrNull()

                if (decrypted == null) {
                    logRepository.error(
                        LogCategory.CRYPTO,
                        "Dropped ${packet.type} id=${shortPid(packet.packetId)} net=${CryptoManager.shortId(packet.networkId)} from=${CryptoManager.shortId(packet.senderId)} (reason=decrypt_failed, pktKeyVer=${packet.keyVersion} localKeyVer=${netEntity.keyVersion})"
                    )
                    return
                }

                // Register verified member pubkeys + run key-change detection
                networkRepository.registerMember(
                    networkId = packet.networkId,
                    userId = packet.senderId,
                    ed25519PubBase64 = packet.senderEd25519Pub,
                    x25519PubBase64 = packet.senderX25519Pub
                )

                when (packetType) {
                    PacketType.TEXT -> {
                        val parsed = runCatching { textAdapter.fromJson(String(decrypted, Charsets.UTF_8)) }.getOrNull()
                        if (parsed != null) {
                            clearTypingFor(packet.networkId, packet.senderId)
                            messageRepository.recordMessage(
                                packetId = packet.packetId,
                                networkId = packet.networkId,
                                senderId = packet.senderId,
                                type = PacketType.TEXT.name,
                                payloadSizeBytes = wireSize,
                                deliveryState = DeliveryState.DELIVERED,
                                hopCount = packet.hopCount,
                                timestampMs = packet.timestampMs,
                                isOutgoing = false,
                                textBody = parsed.body
                            )
                            logRepository.success(
                                LogCategory.MESSAGE,
                                "Received TEXT id=${shortPid(packet.packetId)} net=${CryptoManager.shortId(packet.networkId)} from=${CryptoManager.shortId(packet.senderId)} size=${wireSize}B hops=${packet.hopCount} via=${viaTransport.code}$muteSuffix [delivered]",
                                packetId = packet.packetId,
                                deliveryState = DeliveryState.DELIVERED
                            )
                            sendDeliveryAck(packet, groupKey, netEntity.keyVersion, "DELIVERED")
                        }
                    }

                    PacketType.VOICE_LIVE, PacketType.VOICE_CLIP -> {
                        val voice = runCatching { voiceAdapter.fromJson(String(decrypted, Charsets.UTF_8)) }.getOrNull()
                        if (voice != null) {
                            val audioBytes = runCatching { CryptoManager.decodeB64(voice.audioBase64) }.getOrNull()
                                ?: return
                            messageRepository.recordMessage(
                                packetId = packet.packetId,
                                networkId = packet.networkId,
                                senderId = packet.senderId,
                                type = packetType.name,
                                payloadSizeBytes = wireSize,
                                deliveryState = DeliveryState.DELIVERED,
                                hopCount = packet.hopCount,
                                timestampMs = packet.timestampMs,
                                isOutgoing = false,
                                voiceClip = VoiceClipCache(
                                    streamId = voice.streamId,
                                    codec = voice.codec,
                                    sampleRate = voice.sampleRate,
                                    audioBytes = audioBytes
                                )
                            )
                            logRepository.success(
                                LogCategory.VOICE,
                                "Received ${packetType.name} stream=${voice.streamId}#${voice.seq} net=${CryptoManager.shortId(packet.networkId)} from=${CryptoManager.shortId(packet.senderId)} codec=${voice.codec} size=${audioBytes.size}B hops=${packet.hopCount} via=${viaTransport.code}$muteSuffix [delivered]",
                                packetId = packet.packetId,
                                deliveryState = DeliveryState.DELIVERED
                            )
                            val autoPlay = settingsStore.autoPlayVoice.first()
                            if (isMuted) {
                                logRepository.info(
                                    LogCategory.VOICE,
                                    "Silenced incoming voice stream=${voice.streamId} (user or network muted)"
                                )
                            } else if (autoPlay && !networkRepository.isUserBlockedFast(packet.senderId)) {
                                audioEngine.playIncomingVoice(
                                    senderId = packet.senderId,
                                    streamId = voice.streamId,
                                    codec = voice.codec,
                                    sampleRate = voice.sampleRate,
                                    encodedBytes = audioBytes
                                )
                            } else {
                                logRepository.info(
                                    LogCategory.VOICE,
                                    "Voice auto-play disabled — skipped playback for stream=${voice.streamId}"
                                )
                            }
                            if (packetType == PacketType.VOICE_CLIP || voice.isFinal) {
                                sendDeliveryAck(packet, groupKey, netEntity.keyVersion, "DELIVERED")
                            }
                        }
                    }

                    PacketType.DELIVERY_ACK -> {
                        val ack = runCatching { ackAdapter.fromJson(String(decrypted, Charsets.UTF_8)) }.getOrNull()
                        if (ack != null) {
                            val targetState = if (ack.status == "RELAYED") DeliveryState.RELAYED else DeliveryState.DELIVERED
                            messageRepository.updateDeliveryState(ack.ackedPacketId, targetState, ack.observedHops)
                            logRepository.success(
                                LogCategory.MESSAGE,
                                "Receipt ${targetState.name} for id=${shortPid(ack.ackedPacketId)} by=${CryptoManager.shortId(packet.senderId)} hops=${ack.observedHops} [${targetState.name.lowercase()}]"
                            )
                        }
                    }

                    PacketType.READ_RECEIPT -> {
                        val receipt = runCatching { readReceiptAdapter.fromJson(String(decrypted, Charsets.UTF_8)) }.getOrNull()
                        if (receipt != null) {
                            receipt.readPacketIds.forEach { pid ->
                                messageRepository.updateDeliveryState(pid, DeliveryState.READ)
                            }
                            logRepository.success(
                                LogCategory.MESSAGE,
                                "Read receipt for ${receipt.readPacketIds.size} msg(s) by=${CryptoManager.shortId(packet.senderId)} net=${CryptoManager.shortId(packet.networkId)} [read]"
                            )
                        }
                    }

                    PacketType.TYPING_INDICATOR -> {
                        if (isMuted) return
                        val typing = runCatching { typingAdapter.fromJson(String(decrypted, Charsets.UTF_8)) }.getOrNull()
                            ?: return
                        if (typing.isTyping) {
                            val expiresAt = System.currentTimeMillis() + TYPING_DISPLAY_TIMEOUT_MS
                            _typingPeers.update { current ->
                                current.filterNot { it.networkId == packet.networkId && it.userId == packet.senderId } +
                                    TypingPeer(packet.networkId, packet.senderId, expiresAt)
                            }
                        } else {
                            clearTypingFor(packet.networkId, packet.senderId)
                        }
                        logRepository.info(
                            LogCategory.MESSAGE,
                            "Typing indicator: net=${CryptoManager.shortId(packet.networkId)} from=${CryptoManager.shortId(packet.senderId)} active=${typing.isTyping}"
                        )
                    }

                    else -> Unit
                }
            }

            PacketType.MEMBER_REMOVE -> Unit
        }
    }

    private suspend fun relayIfEligible(packet: MeshPacket, packetType: PacketType, sourceFrame: IncomingFrame) {
        if (packet.ttl <= 1) {
            logRepository.info(
                LogCategory.MESSAGE,
                "Dropped relay for ${packet.type} id=${shortPid(packet.packetId)} (reason=ttl, hops=${packet.hopCount})"
            )
            return
        }

        val relayed = packet.copy(
            ttl = packet.ttl - 1,
            hopCount = packet.hopCount + 1
        )
        val wireJson = packetAdapter.toJson(relayed)
        val wireBytes = wireJson.toByteArray(Charsets.UTF_8)

        val forwardedCount = transportManager.broadcastAcrossAll(
            payloadBytes = wireBytes,
            excludeTransport = sourceFrame.sourceTransport,
            excludePeerId = sourceFrame.sourcePeerId
        )

        val isEphemeral = packetType == PacketType.VOICE_LIVE || packetType == PacketType.TYPING_INDICATOR
        val isReceipt = packetType == PacketType.DELIVERY_ACK || packetType == PacketType.READ_RECEIPT

        if (forwardedCount > 0) {
            logRepository.info(
                LogCategory.MESSAGE,
                "Relayed ${packet.type} id=${shortPid(packet.packetId)} net=${CryptoManager.shortId(packet.networkId)} from=${CryptoManager.shortId(packet.senderId)} size=${wireBytes.size}B hops=${relayed.hopCount} ttl=${relayed.ttl} links=$forwardedCount [relayed]"
            )
            // If this relay node is also a member of the network, emit a lightweight RELAYED receipt
            // for original TEXT/VOICE_CLIP messages so sender's tick updates from sent -> relayed.
            if (packetType == PacketType.TEXT || packetType == PacketType.VOICE_CLIP) {
                val netEntity = networkRepository.getNetworkEntity(packet.networkId)
                val groupKey = networkRepository.getNetworkSymmetricKey(packet.networkId)
                if (netEntity != null && groupKey != null) {
                    sendDeliveryAck(relayed, groupKey, netEntity.keyVersion, "RELAYED")
                }
            }
        } else if (!isEphemeral && !isReceipt) {
            val expiryHours = settingsStore.queueExpiryHours.first()
            val added = messageRepository.enqueueForStoreAndForward(
                packetId = relayed.packetId,
                networkId = relayed.networkId,
                serializedEnvelope = wireJson,
                expiryHours = expiryHours
            )
            if (added) {
                logRepository.info(
                    LogCategory.MESSAGE,
                    "Stored ${packet.type} id=${shortPid(packet.packetId)} in offline queue for relay (hops=${relayed.hopCount}, ttl=${relayed.ttl}, expiry=${expiryHours}h) [queued]"
                )
            }
        }
    }

    private suspend fun sendDeliveryAck(
        original: MeshPacket,
        groupKey: ByteArray,
        keyVersion: Int,
        status: String
    ) {
        val ackPayload = DeliveryAckPayload(
            ackedPacketId = original.packetId,
            receivedAtMs = System.currentTimeMillis(),
            observedHops = original.hopCount,
            status = status
        )
        val ackId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ackPacket = buildSignedGroupPacket(
            packetId = ackId,
            networkId = original.networkId,
            type = PacketType.DELIVERY_ACK,
            keyVersion = keyVersion,
            groupKey = groupKey,
            plaintextBytes = ackAdapter.toJson(ackPayload).toByteArray(Charsets.UTF_8),
            nowMs = now,
            targetUserId = original.senderId,
            ttl = MeshPacket.RECEIPT_MAX_TTL
        )
        seenPacketIds[ackId] = now
        val wire = packetAdapter.toJson(ackPacket).toByteArray(Charsets.UTF_8)
        transportManager.broadcastAcrossAll(wire)
    }

    private suspend fun sendPairwiseKeyDistribution(
        networkId: String,
        networkName: String,
        targetUserId: String,
        targetX25519PubBase64: String,
        keyVersion: Int,
        symmetricKey: ByteArray,
        packetType: PacketType
    ) {
        val identity = cryptoManager.getOrCreateIdentity()
        val peerXPub = runCatching { CryptoManager.decodeB64(targetX25519PubBase64) }.getOrNull() ?: return
        val packetId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val payload = KeyDistributionPayload(
            networkName = networkName,
            ownerId = identity.userId,
            ownerEd25519Pub = identity.ed25519PublicBase64,
            ownerX25519Pub = identity.x25519PublicBase64,
            keyVersion = keyVersion,
            symmetricKeyBase64 = CryptoManager.encodeB64(symmetricKey)
        )
        val aad = "$packetId|$networkId|${identity.userId}|${packetType.name}|$keyVersion".toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encryptPairwise(
            peerX25519PublicKey = peerXPub,
            networkId = networkId,
            plaintext = keyDistAdapter.toJson(payload).toByteArray(Charsets.UTF_8),
            aad = aad
        )
        val unsigned = MeshPacket(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            senderEd25519Pub = identity.ed25519PublicBase64,
            senderX25519Pub = identity.x25519PublicBase64,
            targetUserId = targetUserId,
            type = packetType.name,
            timestampMs = now,
            ttl = MeshPacket.MAX_TTL,
            hopCount = 0,
            keyVersion = keyVersion,
            encryptedPayload = CryptoManager.encodeB64(encrypted),
            signature = ""
        )
        val signed = unsigned.copy(signature = CryptoManager.encodeB64(cryptoManager.sign(unsigned.canonicalSigningBytes())))
        seenPacketIds[packetId] = now
        val wire = packetAdapter.toJson(signed)
        val sent = transportManager.broadcastAcrossAll(wire.toByteArray(Charsets.UTF_8))
        if (sent == 0) {
            val expiryHours = settingsStore.queueExpiryHours.first()
            messageRepository.enqueueForStoreAndForward(packetId, networkId, wire, expiryHours)
        }
    }

    private fun buildSignedGroupPacket(
        packetId: String,
        networkId: String,
        type: PacketType,
        keyVersion: Int,
        groupKey: ByteArray,
        plaintextBytes: ByteArray,
        nowMs: Long,
        targetUserId: String? = null,
        ttl: Int = MeshPacket.MAX_TTL
    ): MeshPacket {
        val identity = cryptoManager.getOrCreateIdentity()
        val aad = "$packetId|$networkId|${identity.userId}|${type.name}|$keyVersion".toByteArray(Charsets.UTF_8)
        val ciphertext = cryptoManager.encryptGroupPayload(groupKey, plaintextBytes, aad)
        val unsigned = MeshPacket(
            packetId = packetId,
            networkId = networkId,
            senderId = identity.userId,
            senderEd25519Pub = identity.ed25519PublicBase64,
            senderX25519Pub = identity.x25519PublicBase64,
            targetUserId = targetUserId,
            type = type.name,
            timestampMs = nowMs,
            ttl = ttl,
            hopCount = 0,
            keyVersion = keyVersion,
            encryptedPayload = CryptoManager.encodeB64(ciphertext),
            signature = ""
        )
        val sig = cryptoManager.sign(unsigned.canonicalSigningBytes())
        return unsigned.copy(signature = CryptoManager.encodeB64(sig))
    }

    suspend fun flushStoreAndForward(forceAll: Boolean = false) {
        val expired = messageRepository.purgeAndReturnExpired()
        expired.forEach { item ->
            logRepository.warn(
                LogCategory.MESSAGE,
                "Offline queue expired id=${shortPid(item.packetId)} net=${CryptoManager.shortId(item.networkId)} after ${item.attempts} attempts [failed]"
            )
        }

        val pending = messageRepository.getDuePendingPackets(forceAll = forceAll)
        if (pending.isEmpty()) return

        var flushed = 0
        pending.forEach { item ->
            val sent = transportManager.broadcastAcrossAll(item.serializedEnvelope.toByteArray(Charsets.UTF_8))
            if (sent > 0) {
                messageRepository.markPendingDispatched(item.packetId)
                messageRepository.updateDeliveryState(item.packetId, DeliveryState.SENT, 0)
                flushed++
            } else {
                messageRepository.scheduleBackoffRetry(
                    packetId = item.packetId,
                    currentAttempts = item.attempts,
                    reason = "No active transport peers"
                )
            }
        }
        if (flushed > 0) {
            logRepository.success(
                LogCategory.MESSAGE,
                "Offline queue flushed $flushed queued packet(s) [sent]"
            )
        }
    }

    private fun clearTypingFor(networkId: String, userId: String) {
        _typingPeers.update { current ->
            current.filterNot { it.networkId == networkId && it.userId == userId }
        }
    }

    private fun pruneExpiredTyping() {
        val now = System.currentTimeMillis()
        _typingPeers.update { current ->
            current.filter { it.expiresAtMs > now }
        }
    }

    private suspend fun broadcastOwnedNetworkAnnouncements() {
        val identity = cryptoManager.getOrCreateIdentity()
        networkRepository.getAllNetworkEntities()
            .filter { it.ownerId == identity.userId }
            .forEach { broadcastNetworkAnnounce(it.networkId) }
    }

    private fun shortPid(packetId: String): String =
        if (packetId.length <= 8) packetId else packetId.substring(0, 8)

    companion object {
        private const val MAX_SEEN_PACKETS = 4000
        private const val TYPING_RATE_LIMIT_MS = 3_500L
        private const val TYPING_DISPLAY_TIMEOUT_MS = 6_000L
        private const val QUEUE_MAINTENANCE_INTERVAL_MS = 10_000L
    }
}
