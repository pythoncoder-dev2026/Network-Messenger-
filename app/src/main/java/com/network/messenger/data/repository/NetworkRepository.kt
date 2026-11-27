package com.network.messenger.data.repository

import android.util.Base64
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.data.local.BlockedUserDao
import com.network.messenger.data.local.BlockedUserEntity
import com.network.messenger.data.local.MeshNetworkEntity
import com.network.messenger.data.local.MutedUserDao
import com.network.messenger.data.local.MutedUserEntity
import com.network.messenger.data.local.NetworkDao
import com.network.messenger.data.local.NetworkMemberEntity
import com.network.messenger.data.local.UserReportDao
import com.network.messenger.data.local.UserReportEntity
import com.network.messenger.domain.model.BlockedUser
import com.network.messenger.domain.model.DiscoveredNetwork
import com.network.messenger.domain.model.KeyChangeAlert
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.MeshInvite
import com.network.messenger.domain.model.MutedUser
import com.network.messenger.domain.model.NetworkMember
import com.network.messenger.domain.model.NetworkRole
import com.network.messenger.domain.model.NetworkSummary
import com.network.messenger.domain.model.UserReport
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CreatedNetworkResult(
    val summary: NetworkSummary,
    val shareCode: String,
    val invite: MeshInvite
)

data class KeyRotationResult(
    val networkId: String,
    val networkName: String,
    val newKeyVersion: Int,
    val newSymmetricKey: ByteArray,
    val remainingMembers: List<NetworkMember>
)

@Singleton
class NetworkRepository @Inject constructor(
    private val networkDao: NetworkDao,
    private val blockedUserDao: BlockedUserDao,
    private val mutedUserDao: MutedUserDao,
    private val userReportDao: UserReportDao,
    private val cryptoManager: CryptoManager,
    private val logRepository: LogRepository,
    private val moshi: Moshi
) {
    private val inviteAdapter = moshi.adapter(MeshInvite::class.java)
    private val _discoveredNetworks = MutableStateFlow<List<DiscoveredNetwork>>(emptyList())
    val discoveredNetworks: StateFlow<List<DiscoveredNetwork>> = _discoveredNetworks.asStateFlow()

    private val _keyChangeAlerts = MutableStateFlow<List<KeyChangeAlert>>(emptyList())
    val keyChangeAlerts: StateFlow<List<KeyChangeAlert>> = _keyChangeAlerts.asStateFlow()

    @Volatile
    private var blockedCache: Set<String> = emptySet()

    @Volatile
    private var mutedUsersCache: Set<String> = emptySet()

    @Volatile
    private var mutedNetworksCache: Set<String> = emptySet()

    val networks: Flow<List<NetworkSummary>> = networkDao.observeAllNetworks().map { list ->
        mutedNetworksCache = list.filter { it.isMuted }.map { it.networkId }.toSet()
        list.map { entity ->
            NetworkSummary(
                networkId = entity.networkId,
                name = entity.name,
                role = runCatching { NetworkRole.valueOf(entity.role) }.getOrDefault(NetworkRole.MEMBER),
                ownerId = entity.ownerId,
                hasPassword = entity.hasPassword,
                keyVersion = entity.keyVersion,
                memberCount = networkDao.getMemberCount(entity.networkId),
                createdAtMs = entity.createdAtMs,
                isMuted = entity.isMuted
            )
        }
    }

    val blockedUsers: Flow<List<BlockedUser>> = blockedUserDao.observeBlockedUsers().map { rows ->
        blockedCache = rows.map { it.userId }.toSet()
        rows.map { BlockedUser(it.userId, it.reason, it.blockedAtMs) }
    }

    val mutedUsers: Flow<List<MutedUser>> = mutedUserDao.observeMutedUsers().map { rows ->
        mutedUsersCache = rows.map { it.userId }.toSet()
        rows.map { MutedUser(it.userId, it.mutedAtMs) }
    }

    val userReports: Flow<List<UserReport>> = userReportDao.observeReports().map { rows ->
        rows.map {
            UserReport(
                reportId = it.reportId,
                reportedUserId = it.reportedUserId,
                networkId = it.networkId,
                reason = it.reason,
                sentToOwner = it.sentToOwner,
                createdAtMs = it.createdAtMs
            )
        }
    }

    suspend fun warmCaches() {
        blockedCache = blockedUserDao.getBlockedUserIds().toSet()
        mutedUsersCache = mutedUserDao.getMutedUserIds().toSet()
        mutedNetworksCache = networkDao.getAllNetworks().filter { it.isMuted }.map { it.networkId }.toSet()
    }

    suspend fun warmBlockedCache() = warmCaches()

    fun isUserBlockedFast(userId: String): Boolean = blockedCache.contains(userId)

    fun isUserMutedFast(userId: String): Boolean = mutedUsersCache.contains(userId)

    fun isNetworkMutedFast(networkId: String): Boolean = mutedNetworksCache.contains(networkId)

    suspend fun isUserBlocked(userId: String): Boolean {
        if (blockedCache.contains(userId)) return true
        return blockedUserDao.isBlocked(userId)
    }

    fun observeMembers(networkId: String): Flow<List<NetworkMember>> =
        networkDao.observeMembers(networkId).map { rows ->
            rows.map { row ->
                NetworkMember(
                    networkId = row.networkId,
                    userId = row.userId,
                    ed25519PubBase64 = row.ed25519PubBase64,
                    x25519PubBase64 = row.x25519PubBase64,
                    role = runCatching { NetworkRole.valueOf(row.role) }.getOrDefault(NetworkRole.MEMBER),
                    joinedAtMs = row.joinedAtMs,
                    verified = row.verified,
                    verifiedAtMs = row.verifiedAtMs,
                    keyChangedWarning = row.keyChangedWarning,
                    previousKeyFingerprint = row.previousKeyFingerprint,
                    safetyNumber = cryptoManager.computeSafetyNumber(row.ed25519PubBase64, row.x25519PubBase64)
                )
            }
        }

    suspend fun getAllNetworkEntities(): List<MeshNetworkEntity> = networkDao.getAllNetworks()

    suspend fun getNetworkEntity(networkId: String): MeshNetworkEntity? =
        networkDao.getNetworkById(networkId)

    suspend fun getNetworkSymmetricKey(networkId: String): ByteArray? {
        val entity = networkDao.getNetworkById(networkId) ?: return null
        if (entity.wrappedSymmetricKey.isBlank()) return null
        return runCatching { cryptoManager.unwrapFromLocalStorage(entity.wrappedSymmetricKey) }.getOrNull()
    }

    suspend fun createNetwork(
        name: String,
        password: String?,
        relayHint: String?
    ): CreatedNetworkResult {
        val cleanName = name.trim().ifEmpty { "Mesh-${System.currentTimeMillis() % 1000}" }
        val identity = cryptoManager.getOrCreateIdentity()
        val networkId = cryptoManager.generateNetworkId()
        val symmetricKey = cryptoManager.generateNetworkKey()
        val now = System.currentTimeMillis()

        val hasPassword = !password.isNullOrBlank()
        var saltB64: String? = null
        var wrappedPwdHash: String? = null
        var inviteWrappedKey: String? = null

        if (hasPassword) {
            val salt = cryptoManager.generateRandomBytes(16)
            saltB64 = CryptoManager.encodeB64(salt)
            val pwdKey = cryptoManager.derivePasswordKey(password!!, salt, networkId)
            val pwdDigest = MessageDigest.getInstance("SHA-256").digest(pwdKey)
            wrappedPwdHash = cryptoManager.wrapForLocalStorage(pwdDigest)
            val encryptedGroupKey = cryptoManager.encryptGroupPayload(
                pwdKey,
                symmetricKey,
                "invite|$networkId|1".toByteArray(Charsets.UTF_8)
            )
            inviteWrappedKey = CryptoManager.encodeB64(encryptedGroupKey)
        } else {
            inviteWrappedKey = CryptoManager.encodeB64(symmetricKey)
        }

        val entity = MeshNetworkEntity(
            networkId = networkId,
            name = cleanName,
            role = NetworkRole.OWNER.name,
            ownerId = identity.userId,
            ownerEd25519PubBase64 = identity.ed25519PublicBase64,
            ownerX25519PubBase64 = identity.x25519PublicBase64,
            hasPassword = hasPassword,
            passwordSaltBase64 = saltB64,
            wrappedPasswordHash = wrappedPwdHash,
            wrappedSymmetricKey = cryptoManager.wrapForLocalStorage(symmetricKey),
            keyVersion = 1,
            createdAtMs = now,
            isMuted = false
        )
        networkDao.upsertNetwork(entity)
        networkDao.upsertMember(
            NetworkMemberEntity(
                networkId = networkId,
                userId = identity.userId,
                ed25519PubBase64 = identity.ed25519PublicBase64,
                x25519PubBase64 = identity.x25519PublicBase64,
                role = NetworkRole.OWNER.name,
                joinedAtMs = now,
                verified = true,
                verifiedAtMs = now
            )
        )

        val invite = MeshInvite(
            networkId = networkId,
            name = cleanName,
            ownerId = identity.userId,
            ownerEd25519Pub = identity.ed25519PublicBase64,
            ownerX25519Pub = identity.x25519PublicBase64,
            hasPassword = hasPassword,
            passwordSalt = saltB64,
            wrappedKey = inviteWrappedKey,
            keyVersion = 1,
            relayHint = relayHint?.takeIf { it.isNotBlank() }
        )
        val shareCode = encodeInvite(invite)

        logRepository.success(
            LogCategory.NETWORK,
            "Network created: \"$cleanName\" id=${CryptoManager.shortId(networkId)} role=OWNER pwd=$hasPassword keyVer=1"
        )

        return CreatedNetworkResult(
            summary = NetworkSummary(
                networkId = networkId,
                name = cleanName,
                role = NetworkRole.OWNER,
                ownerId = identity.userId,
                hasPassword = hasPassword,
                keyVersion = 1,
                memberCount = 1,
                createdAtMs = now,
                isMuted = false
            ),
            shareCode = shareCode,
            invite = invite
        )
    }

    suspend fun buildShareCodeForExisting(networkId: String, passwordForWrap: String? = null, relayHint: String? = null): String? {
        val entity = networkDao.getNetworkById(networkId) ?: return null
        val key = getNetworkSymmetricKey(networkId)
        val wrappedKey = when {
            key == null -> null
            entity.hasPassword && !passwordForWrap.isNullOrBlank() && entity.passwordSaltBase64 != null -> {
                val salt = CryptoManager.decodeB64(entity.passwordSaltBase64)
                val pwdKey = cryptoManager.derivePasswordKey(passwordForWrap, salt, networkId)
                CryptoManager.encodeB64(
                    cryptoManager.encryptGroupPayload(
                        pwdKey,
                        key,
                        "invite|$networkId|${entity.keyVersion}".toByteArray(Charsets.UTF_8)
                    )
                )
            }
            !entity.hasPassword -> CryptoManager.encodeB64(key)
            else -> null
        }
        val invite = MeshInvite(
            networkId = entity.networkId,
            name = entity.name,
            ownerId = entity.ownerId,
            ownerEd25519Pub = entity.ownerEd25519PubBase64,
            ownerX25519Pub = entity.ownerX25519PubBase64,
            hasPassword = entity.hasPassword,
            passwordSalt = entity.passwordSaltBase64,
            wrappedKey = wrappedKey,
            keyVersion = entity.keyVersion,
            relayHint = relayHint?.takeIf { it.isNotBlank() }
        )
        return encodeInvite(invite)
    }

    fun decodeInvite(code: String): MeshInvite? {
        val trimmed = code.trim()
        val payload = if (trimmed.startsWith(INVITE_PREFIX)) {
            trimmed.removePrefix(INVITE_PREFIX)
        } else {
            trimmed
        }
        return runCatching {
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            inviteAdapter.fromJson(json)
        }.getOrNull()
    }

    fun encodeInvite(invite: MeshInvite): String {
        val json = inviteAdapter.toJson(invite)
        val encoded = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$INVITE_PREFIX$encoded"
    }

    suspend fun joinFromInvite(invite: MeshInvite, password: String?): Pair<MeshNetworkEntity, Boolean> {
        val identity = cryptoManager.getOrCreateIdentity()
        val now = System.currentTimeMillis()
        var unlockedKey: ByteArray? = null

        if (invite.wrappedKey != null) {
            if (invite.hasPassword) {
                if (password.isNullOrBlank() || invite.passwordSalt == null) {
                    throw IllegalArgumentException("Password required to unlock network invite")
                }
                val salt = CryptoManager.decodeB64(invite.passwordSalt)
                val pwdKey = cryptoManager.derivePasswordKey(password, salt, invite.networkId)
                val encrypted = CryptoManager.decodeB64(invite.wrappedKey)
                unlockedKey = cryptoManager.decryptGroupPayload(
                    pwdKey,
                    encrypted,
                    "invite|${invite.networkId}|${invite.keyVersion}".toByteArray(Charsets.UTF_8)
                )
            } else {
                unlockedKey = CryptoManager.decodeB64(invite.wrappedKey)
            }
        }

        val role = if (invite.ownerId == identity.userId) NetworkRole.OWNER else NetworkRole.MEMBER
        val entity = MeshNetworkEntity(
            networkId = invite.networkId,
            name = invite.name,
            role = role.name,
            ownerId = invite.ownerId,
            ownerEd25519PubBase64 = invite.ownerEd25519Pub,
            ownerX25519PubBase64 = invite.ownerX25519Pub,
            hasPassword = invite.hasPassword,
            passwordSaltBase64 = invite.passwordSalt,
            wrappedPasswordHash = null,
            wrappedSymmetricKey = unlockedKey?.let { cryptoManager.wrapForLocalStorage(it) }.orEmpty(),
            keyVersion = invite.keyVersion,
            createdAtMs = now,
            isMuted = false
        )
        networkDao.upsertNetwork(entity)
        registerMember(
            networkId = invite.networkId,
            userId = invite.ownerId,
            ed25519PubBase64 = invite.ownerEd25519Pub,
            x25519PubBase64 = invite.ownerX25519Pub,
            role = NetworkRole.OWNER
        )
        registerMember(
            networkId = invite.networkId,
            userId = identity.userId,
            ed25519PubBase64 = identity.ed25519PublicBase64,
            x25519PubBase64 = identity.x25519PublicBase64,
            role = role
        )

        val keyReady = unlockedKey != null
        logRepository.success(
            LogCategory.NETWORK,
            "Network joined: \"${invite.name}\" id=${CryptoManager.shortId(invite.networkId)} role=$role keyReady=$keyReady"
        )
        return entity to keyReady
    }

    suspend fun joinDiscoveredNetwork(discovered: DiscoveredNetwork): MeshNetworkEntity {
        val identity = cryptoManager.getOrCreateIdentity()
        val now = System.currentTimeMillis()
        val existing = networkDao.getNetworkById(discovered.networkId)
        val entity = existing ?: MeshNetworkEntity(
            networkId = discovered.networkId,
            name = discovered.name,
            role = NetworkRole.MEMBER.name,
            ownerId = discovered.ownerId,
            ownerEd25519PubBase64 = discovered.ownerEd25519PubBase64,
            ownerX25519PubBase64 = discovered.ownerX25519PubBase64,
            hasPassword = discovered.hasPassword,
            passwordSaltBase64 = null,
            wrappedPasswordHash = null,
            wrappedSymmetricKey = "",
            keyVersion = 0,
            createdAtMs = now,
            isMuted = false
        )
        networkDao.upsertNetwork(entity)
        registerMember(
            networkId = discovered.networkId,
            userId = discovered.ownerId,
            ed25519PubBase64 = discovered.ownerEd25519PubBase64,
            x25519PubBase64 = discovered.ownerX25519PubBase64,
            role = NetworkRole.OWNER
        )
        registerMember(
            networkId = discovered.networkId,
            userId = identity.userId,
            ed25519PubBase64 = identity.ed25519PublicBase64,
            x25519PubBase64 = identity.x25519PublicBase64,
            role = NetworkRole.MEMBER
        )
        logRepository.info(
            LogCategory.NETWORK,
            "Join requested for nearby network \"${discovered.name}\" id=${CryptoManager.shortId(discovered.networkId)} via ${discovered.viaTransport.code}; awaiting X25519 key exchange"
        )
        return entity
    }

    suspend fun applyReceivedNetworkKey(
        networkId: String,
        networkName: String,
        ownerId: String,
        ownerEd25519Pub: String,
        ownerX25519Pub: String,
        keyVersion: Int,
        symmetricKey: ByteArray
    ) {
        val existing = networkDao.getNetworkById(networkId)
        val identity = cryptoManager.getOrCreateIdentity()
        val updated = if (existing != null) {
            existing.copy(
                name = networkName,
                wrappedSymmetricKey = cryptoManager.wrapForLocalStorage(symmetricKey),
                keyVersion = keyVersion
            )
        } else {
            MeshNetworkEntity(
                networkId = networkId,
                name = networkName,
                role = if (ownerId == identity.userId) NetworkRole.OWNER.name else NetworkRole.MEMBER.name,
                ownerId = ownerId,
                ownerEd25519PubBase64 = ownerEd25519Pub,
                ownerX25519PubBase64 = ownerX25519Pub,
                hasPassword = false,
                passwordSaltBase64 = null,
                wrappedPasswordHash = null,
                wrappedSymmetricKey = cryptoManager.wrapForLocalStorage(symmetricKey),
                keyVersion = keyVersion,
                createdAtMs = System.currentTimeMillis(),
                isMuted = false
            )
        }
        networkDao.upsertNetwork(updated)
    }

    /**
     * Registers or updates a network member while detecting identity key changes on known contacts.
     * Logs a loud ERROR alert and emits a UI warning if a previously seen contact's keys change.
     */
    suspend fun registerMember(
        networkId: String,
        userId: String,
        ed25519PubBase64: String,
        x25519PubBase64: String,
        role: NetworkRole = NetworkRole.MEMBER
    ) {
        val existingAcrossNets = networkDao.getMembersByUserId(userId)
        val prior = existingAcrossNets.firstOrNull()
        val localId = cryptoManager.getOrCreateIdentity().userId

        if (prior != null &&
            (prior.ed25519PubBase64 != ed25519PubBase64 || prior.x25519PubBase64 != x25519PubBase64)
        ) {
            val prevFp = CryptoManager.computeKeyFingerprint(prior.ed25519PubBase64, prior.x25519PubBase64)
            val newFp = CryptoManager.computeKeyFingerprint(ed25519PubBase64, x25519PubBase64)
            networkDao.flagKeyChanged(userId, prevFp, ed25519PubBase64, x25519PubBase64)
            val alert = KeyChangeAlert(
                userId = userId,
                networkId = networkId,
                previousFingerprint = prevFp,
                newFingerprint = newFp,
                detectedAtMs = System.currentTimeMillis()
            )
            _keyChangeAlerts.update { current ->
                current.filterNot { it.userId == userId } + alert
            }
            logRepository.error(
                LogCategory.CRYPTO,
                "SECURITY WARNING: Identity key changed for uid=${CryptoManager.shortId(userId)} (wasVerified=${prior.verified}) — re-verify safety number!"
            )
        }

        val currentInNet = networkDao.getMember(networkId, userId)
        val isSelf = userId == localId
        networkDao.upsertMember(
            NetworkMemberEntity(
                networkId = networkId,
                userId = userId,
                ed25519PubBase64 = ed25519PubBase64,
                x25519PubBase64 = x25519PubBase64,
                role = currentInNet?.role ?: role.name,
                joinedAtMs = currentInNet?.joinedAtMs ?: System.currentTimeMillis(),
                verified = if (isSelf) true else (currentInNet?.verified ?: prior?.verified ?: false),
                verifiedAtMs = if (isSelf) System.currentTimeMillis() else (currentInNet?.verifiedAtMs ?: prior?.verifiedAtMs),
                keyChangedWarning = currentInNet?.keyChangedWarning ?: false,
                previousKeyFingerprint = currentInNet?.previousKeyFingerprint
            )
        )
    }

    /**
     * Scans a peer's verification QR (`NMSGV1:<userId>:<fingerprint>`) and marks them Verified
     * if their public key fingerprint matches, or raises a loud key-change/mismatch warning.
     */
    suspend fun verifyContactFromQr(expectedUserId: String?, scannedRaw: String): Boolean {
        val parsed = CryptoManager.parseVerificationQr(scannedRaw)
        if (parsed == null) {
            logRepository.error(LogCategory.CRYPTO, "Key verification failed: invalid verification QR format")
            return false
        }
        val (scannedUserId, scannedFp) = parsed
        if (expectedUserId != null && expectedUserId != scannedUserId) {
            logRepository.error(
                LogCategory.CRYPTO,
                "KEY VERIFICATION MISMATCH: expected uid=${CryptoManager.shortId(expectedUserId)} but scanned uid=${CryptoManager.shortId(scannedUserId)}"
            )
            return false
        }
        val records = networkDao.getMembersByUserId(scannedUserId)
        val member = records.firstOrNull()
        if (member == null) {
            logRepository.warn(
                LogCategory.CRYPTO,
                "Cannot verify uid=${CryptoManager.shortId(scannedUserId)}: contact not yet in any joined network"
            )
            return false
        }
        val storedFp = CryptoManager.computeKeyFingerprint(member.ed25519PubBase64, member.x25519PubBase64)
        if (!MessageDigest.isEqual(storedFp.toByteArray(), scannedFp.toByteArray())) {
            val alert = KeyChangeAlert(
                userId = scannedUserId,
                networkId = member.networkId,
                previousFingerprint = storedFp,
                newFingerprint = scannedFp,
                detectedAtMs = System.currentTimeMillis()
            )
            _keyChangeAlerts.update { it.filterNot { a -> a.userId == scannedUserId } + alert }
            logRepository.error(
                LogCategory.CRYPTO,
                "SECURITY WARNING: Scanned key fingerprint mismatch for uid=${CryptoManager.shortId(scannedUserId)} — possible key change or MITM!"
            )
            return false
        }
        val now = System.currentTimeMillis()
        networkDao.markUserVerified(scannedUserId, verified = true, verifiedAtMs = now)
        _keyChangeAlerts.update { it.filterNot { a -> a.userId == scannedUserId } }
        val safetyPrefix = cryptoManager.computeSafetyNumber(member.ed25519PubBase64, member.x25519PubBase64).take(11)
        logRepository.success(
            LogCategory.CRYPTO,
            "Contact VERIFIED via QR: uid=${CryptoManager.shortId(scannedUserId)} safety=$safetyPrefix…"
        )
        return true
    }

    suspend fun markContactVerifiedManually(userId: String) {
        val now = System.currentTimeMillis()
        networkDao.markUserVerified(userId, verified = true, verifiedAtMs = now)
        _keyChangeAlerts.update { it.filterNot { a -> a.userId == userId } }
        logRepository.success(
            LogCategory.CRYPTO,
            "Contact VERIFIED manually (safety number confirmed): uid=${CryptoManager.shortId(userId)}"
        )
    }

    fun dismissKeyChangeAlert(userId: String) {
        _keyChangeAlerts.update { it.filterNot { a -> a.userId == userId } }
    }

    suspend fun setNetworkMuted(networkId: String, muted: Boolean) {
        networkDao.setNetworkMuted(networkId, muted)
        mutedNetworksCache = if (muted) mutedNetworksCache + networkId else mutedNetworksCache - networkId
        logRepository.info(
            LogCategory.NETWORK,
            "Network ${if (muted) "muted" else "unmuted"}: id=${CryptoManager.shortId(networkId)} (still connected & relaying)"
        )
    }

    suspend fun muteUser(userId: String) {
        val clean = userId.trim()
        if (clean.isEmpty()) return
        mutedUserDao.muteUser(MutedUserEntity(clean, System.currentTimeMillis()))
        mutedUsersCache = mutedUsersCache + clean
        logRepository.info(
            LogCategory.BLOCK,
            "User muted: uid=${CryptoManager.shortId(clean)} (stays connected, voice & typing silenced)"
        )
    }

    suspend fun unmuteUser(userId: String) {
        val clean = userId.trim()
        mutedUserDao.unmuteUser(clean)
        mutedUsersCache = mutedUsersCache - clean
        logRepository.info(
            LogCategory.BLOCK,
            "User unmuted: uid=${CryptoManager.shortId(clean)}"
        )
    }

    suspend fun createLocalReport(
        reportedUserId: String,
        networkId: String?,
        reason: String,
        sentToOwner: Boolean
    ): UserReport {
        val report = UserReport(
            reportId = UUID.randomUUID().toString(),
            reportedUserId = reportedUserId.trim(),
            networkId = networkId,
            reason = reason.trim().ifEmpty { "Abuse / spam" },
            sentToOwner = sentToOwner,
            createdAtMs = System.currentTimeMillis()
        )
        userReportDao.insertReport(
            UserReportEntity(
                reportId = report.reportId,
                reportedUserId = report.reportedUserId,
                networkId = report.networkId,
                reason = report.reason,
                sentToOwner = report.sentToOwner,
                createdAtMs = report.createdAtMs
            )
        )
        logRepository.warn(
            LogCategory.BLOCK,
            "User reported locally: uid=${CryptoManager.shortId(report.reportedUserId)} net=${networkId?.let { CryptoManager.shortId(it) } ?: "global"} sentToOwner=$sentToOwner"
        )
        return report
    }

    suspend fun storeIncomingOwnerReport(
        reportId: String,
        reportedUserId: String,
        networkId: String,
        reason: String,
        createdAtMs: Long
    ) {
        userReportDao.insertReport(
            UserReportEntity(
                reportId = reportId,
                reportedUserId = reportedUserId,
                networkId = networkId,
                reason = reason,
                sentToOwner = true,
                createdAtMs = createdAtMs
            )
        )
    }

    suspend fun verifyPasswordProof(networkId: String, passwordProofBase64: String?): Boolean {
        val entity = networkDao.getNetworkById(networkId) ?: return false
        if (!entity.hasPassword) return true
        val storedWrapped = entity.wrappedPasswordHash ?: return false
        val provided = passwordProofBase64?.let { runCatching { CryptoManager.decodeB64(it) }.getOrNull() }
            ?: return false
        val expected = runCatching { cryptoManager.unwrapFromLocalStorage(storedWrapped) }.getOrNull()
            ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    suspend fun leaveNetwork(networkId: String) {
        val entity = networkDao.getNetworkById(networkId)
        networkDao.deleteAllMembers(networkId)
        networkDao.deleteNetwork(networkId)
        mutedNetworksCache = mutedNetworksCache - networkId
        logRepository.warn(
            LogCategory.NETWORK,
            "Network left: \"${entity?.name ?: CryptoManager.shortId(networkId)}\" id=${CryptoManager.shortId(networkId)}"
        )
    }

    suspend fun removeMemberAndRotateKey(networkId: String, targetUserId: String): KeyRotationResult? {
        val entity = networkDao.getNetworkById(networkId) ?: return null
        val identity = cryptoManager.getOrCreateIdentity()
        if (entity.ownerId != identity.userId) {
            logRepository.error(
                LogCategory.NETWORK,
                "Only network owner can remove member ${CryptoManager.shortId(targetUserId)}"
            )
            return null
        }
        if (targetUserId == identity.userId) return null

        networkDao.deleteMember(networkId, targetUserId)
        logRepository.warn(
            LogCategory.NETWORK,
            "Member removed from ${CryptoManager.shortId(networkId)}: uid=${CryptoManager.shortId(targetUserId)}"
        )
        return rotateNetworkKey(networkId)
    }

    suspend fun rotateNetworkKey(networkId: String): KeyRotationResult? {
        val entity = networkDao.getNetworkById(networkId) ?: return null
        val identity = cryptoManager.getOrCreateIdentity()
        if (entity.ownerId != identity.userId) {
            logRepository.error(LogCategory.CRYPTO, "Key rotation denied: local node is not owner")
            return null
        }

        val newKey = cryptoManager.generateNetworkKey()
        val newVersion = entity.keyVersion + 1
        networkDao.upsertNetwork(
            entity.copy(
                wrappedSymmetricKey = cryptoManager.wrapForLocalStorage(newKey),
                keyVersion = newVersion
            )
        )

        val remainingMembers = networkDao.getMembers(networkId)
            .filter { it.userId != identity.userId && !isUserBlocked(it.userId) }
            .map {
                NetworkMember(
                    networkId = it.networkId,
                    userId = it.userId,
                    ed25519PubBase64 = it.ed25519PubBase64,
                    x25519PubBase64 = it.x25519PubBase64,
                    role = runCatching { NetworkRole.valueOf(it.role) }.getOrDefault(NetworkRole.MEMBER),
                    joinedAtMs = it.joinedAtMs,
                    verified = it.verified,
                    verifiedAtMs = it.verifiedAtMs,
                    keyChangedWarning = it.keyChangedWarning,
                    previousKeyFingerprint = it.previousKeyFingerprint
                )
            }

        logRepository.success(
            LogCategory.CRYPTO,
            "Network key rotated: net=${CryptoManager.shortId(networkId)} keyVer=$newVersion recipients=${remainingMembers.size}"
        )

        return KeyRotationResult(
            networkId = networkId,
            networkName = entity.name,
            newKeyVersion = newVersion,
            newSymmetricKey = newKey,
            remainingMembers = remainingMembers
        )
    }

    suspend fun blockUser(userId: String, reason: String = "Manual block"): List<KeyRotationResult> {
        val cleanId = userId.trim()
        if (cleanId.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        blockedUserDao.blockUser(BlockedUserEntity(cleanId, reason, now))
        blockedCache = blockedCache + cleanId
        logRepository.warn(
            LogCategory.BLOCK,
            "User blocked: uid=${CryptoManager.shortId(cleanId)} reason=\"$reason\" (packets & voice will be dropped)"
        )

        val identity = cryptoManager.getOrCreateIdentity()
        val rotations = mutableListOf<KeyRotationResult>()
        networkDao.getAllNetworks()
            .filter { it.ownerId == identity.userId }
            .forEach { net ->
                val members = networkDao.getMembers(net.networkId)
                if (members.any { it.userId == cleanId }) {
                    removeMemberAndRotateKey(net.networkId, cleanId)?.let { rotations.add(it) }
                }
            }
        return rotations
    }

    suspend fun unblockUser(userId: String) {
        val cleanId = userId.trim()
        blockedUserDao.unblockUser(cleanId)
        blockedCache = blockedCache - cleanId
        logRepository.info(
            LogCategory.BLOCK,
            "User unblocked: uid=${CryptoManager.shortId(cleanId)}"
        )
    }

    fun recordDiscoveredNetwork(network: DiscoveredNetwork) {
        _discoveredNetworks.update { current ->
            val without = current.filterNot { it.networkId == network.networkId }
            (without + network).sortedByDescending { it.discoveredAtMs }
        }
    }

    companion object {
        const val INVITE_PREFIX = "NMSG1:"
    }
}
