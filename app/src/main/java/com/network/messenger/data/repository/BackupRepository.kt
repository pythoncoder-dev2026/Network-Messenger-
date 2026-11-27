package com.network.messenger.data.repository

import androidx.room.withTransaction
import com.network.messenger.crypto.CryptoManager
import com.network.messenger.crypto.ExportedIdentityKeys
import com.network.messenger.crypto.LocalIdentity
import com.network.messenger.data.local.AppDatabase
import com.network.messenger.data.local.BlockedUserDao
import com.network.messenger.data.local.BlockedUserEntity
import com.network.messenger.data.local.MeshNetworkEntity
import com.network.messenger.data.local.MutedUserDao
import com.network.messenger.data.local.MutedUserEntity
import com.network.messenger.data.local.NetworkDao
import com.network.messenger.data.local.NetworkMemberEntity
import com.network.messenger.domain.model.BackupBlockedRecord
import com.network.messenger.domain.model.BackupBundlePayload
import com.network.messenger.domain.model.BackupMemberRecord
import com.network.messenger.domain.model.BackupMutedRecord
import com.network.messenger.domain.model.BackupNetworkRecord
import com.network.messenger.domain.model.EncryptedBackupEnvelope
import com.network.messenger.domain.model.LogCategory
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

data class BackupSummary(
    val userId: String,
    val networkCount: Int,
    val contactCount: Int,
    val blockedCount: Int,
    val mutedCount: Int,
    val sizeBytes: Int
)

/**
 * Exports and restores identity keys, contacts, networks, mute list, and block list
 * into a passphrase-encrypted file using PBKDF2-HMAC-SHA256 + Tink AES-256-GCM.
 * Never logs raw keys, passphrases, or decrypted backup contents.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val networkDao: NetworkDao,
    private val blockedUserDao: BlockedUserDao,
    private val mutedUserDao: MutedUserDao,
    private val networkRepository: NetworkRepository,
    private val cryptoManager: CryptoManager,
    private val logRepository: LogRepository,
    private val moshi: Moshi
) {
    private val envelopeAdapter = moshi.adapter(EncryptedBackupEnvelope::class.java)
    private val payloadAdapter = moshi.adapter(BackupBundlePayload::class.java)

    suspend fun createEncryptedBackupBytes(passphrase: String): Pair<ByteArray, BackupSummary> {
        require(passphrase.length >= 6) { "Passphrase must be at least 6 characters" }
        val identity = cryptoManager.getOrCreateIdentity()
        val exportedKeys = cryptoManager.exportIdentityKeysForBackup()

        val networks = networkDao.getAllNetworks().map { net ->
            val rawSymKey = if (net.wrappedSymmetricKey.isNotBlank()) {
                runCatching { cryptoManager.unwrapFromLocalStorage(net.wrappedSymmetricKey) }
                    .getOrDefault(ByteArray(0))
            } else {
                ByteArray(0)
            }
            BackupNetworkRecord(
                networkId = net.networkId,
                name = net.name,
                role = net.role,
                ownerId = net.ownerId,
                ownerEd25519PubBase64 = net.ownerEd25519PubBase64,
                ownerX25519PubBase64 = net.ownerX25519PubBase64,
                hasPassword = net.hasPassword,
                passwordSaltBase64 = net.passwordSaltBase64,
                rawSymmetricKeyBase64 = if (rawSymKey.isNotEmpty()) CryptoManager.encodeB64(rawSymKey) else "",
                keyVersion = net.keyVersion,
                createdAtMs = net.createdAtMs,
                isMuted = net.isMuted
            )
        }

        val members = networkDao.getAllMembers().map { m ->
            BackupMemberRecord(
                networkId = m.networkId,
                userId = m.userId,
                ed25519PubBase64 = m.ed25519PubBase64,
                x25519PubBase64 = m.x25519PubBase64,
                role = m.role,
                joinedAtMs = m.joinedAtMs,
                verified = m.verified,
                verifiedAtMs = m.verifiedAtMs
            )
        }

        val blocked = blockedUserDao.getAllBlockedUsers().map {
            BackupBlockedRecord(it.userId, it.reason, it.blockedAtMs)
        }

        val muted = mutedUserDao.getAllMutedUsers().map {
            BackupMutedRecord(it.userId, it.mutedAtMs)
        }

        val payload = BackupBundlePayload(
            exportedAtMs = System.currentTimeMillis(),
            ed25519PrivBase64 = exportedKeys.ed25519PrivBase64,
            ed25519PubBase64 = exportedKeys.ed25519PubBase64,
            x25519PrivBase64 = exportedKeys.x25519PrivBase64,
            x25519PubBase64 = exportedKeys.x25519PubBase64,
            networks = networks,
            members = members,
            blockedUsers = blocked,
            mutedUsers = muted
        )

        val plaintextBytes = payloadAdapter.toJson(payload).toByteArray(Charsets.UTF_8)
        val (salt, ciphertext) = cryptoManager.encryptBackupPayload(
            passphrase = passphrase,
            plaintext = plaintextBytes,
            iterations = CryptoManager.BACKUP_PBKDF2_ITERATIONS
        )
        plaintextBytes.fill(0)

        val envelope = EncryptedBackupEnvelope(
            iterations = CryptoManager.BACKUP_PBKDF2_ITERATIONS,
            saltBase64 = CryptoManager.encodeB64(salt),
            ciphertextBase64 = CryptoManager.encodeB64(ciphertext),
            createdAtMs = System.currentTimeMillis()
        )
        val outBytes = envelopeAdapter.toJson(envelope).toByteArray(Charsets.UTF_8)
        val summary = BackupSummary(
            userId = identity.userId,
            networkCount = networks.size,
            contactCount = members.size,
            blockedCount = blocked.size,
            mutedCount = muted.size,
            sizeBytes = outBytes.size
        )

        logRepository.success(
            LogCategory.CRYPTO,
            "Encrypted backup exported (PBKDF2+AES-256-GCM): size=${outBytes.size}B nets=${networks.size} contacts=${members.size} blocked=${blocked.size} muted=${muted.size}"
        )
        return outBytes to summary
    }

    suspend fun restoreEncryptedBackupBytes(passphrase: String, fileBytes: ByteArray): Pair<LocalIdentity, BackupSummary> {
        val envelope = envelopeAdapter.fromJson(String(fileBytes, Charsets.UTF_8))
            ?: throw IllegalArgumentException("Invalid backup file format")
        require(envelope.format == "NMSG_BACKUP_V1") { "Unsupported backup format: ${envelope.format}" }
        require(envelope.iterations in 50_000..1_000_000) { "Invalid backup KDF iteration count" }

        val salt = CryptoManager.decodeB64(envelope.saltBase64)
        val ciphertext = CryptoManager.decodeB64(envelope.ciphertextBase64)
        val decryptedBytes = cryptoManager.decryptBackupPayload(
            passphrase = passphrase,
            salt = salt,
            ciphertext = ciphertext,
            iterations = envelope.iterations
        )

        val payload = try {
            payloadAdapter.fromJson(String(decryptedBytes, Charsets.UTF_8))
                ?: throw IllegalArgumentException("Corrupt backup payload")
        } finally {
            decryptedBytes.fill(0)
        }

        val restoredIdentity = cryptoManager.importIdentityKeysFromBackup(
            ExportedIdentityKeys(
                ed25519PrivBase64 = payload.ed25519PrivBase64,
                ed25519PubBase64 = payload.ed25519PubBase64,
                x25519PrivBase64 = payload.x25519PrivBase64,
                x25519PubBase64 = payload.x25519PubBase64
            )
        )

        appDatabase.withTransaction {
            payload.networks.forEach { net ->
                val wrappedSym = if (net.rawSymmetricKeyBase64.isNotBlank()) {
                    cryptoManager.wrapForLocalStorage(CryptoManager.decodeB64(net.rawSymmetricKeyBase64))
                } else {
                    ""
                }
                networkDao.upsertNetwork(
                    MeshNetworkEntity(
                        networkId = net.networkId,
                        name = net.name,
                        role = net.role,
                        ownerId = net.ownerId,
                        ownerEd25519PubBase64 = net.ownerEd25519PubBase64,
                        ownerX25519PubBase64 = net.ownerX25519PubBase64,
                        hasPassword = net.hasPassword,
                        passwordSaltBase64 = net.passwordSaltBase64,
                        wrappedPasswordHash = null,
                        wrappedSymmetricKey = wrappedSym,
                        keyVersion = net.keyVersion,
                        createdAtMs = net.createdAtMs,
                        isMuted = net.isMuted
                    )
                )
            }
            payload.members.forEach { m ->
                networkDao.upsertMember(
                    NetworkMemberEntity(
                        networkId = m.networkId,
                        userId = m.userId,
                        ed25519PubBase64 = m.ed25519PubBase64,
                        x25519PubBase64 = m.x25519PubBase64,
                        role = m.role,
                        joinedAtMs = m.joinedAtMs,
                        verified = m.verified,
                        verifiedAtMs = m.verifiedAtMs,
                        keyChangedWarning = false,
                        previousKeyFingerprint = null
                    )
                )
            }
            payload.blockedUsers.forEach { b ->
                blockedUserDao.blockUser(BlockedUserEntity(b.userId, b.reason, b.blockedAtMs))
            }
            payload.mutedUsers.forEach { u ->
                mutedUserDao.muteUser(MutedUserEntity(u.userId, u.mutedAtMs))
            }
        }

        networkRepository.warmCaches()
        logRepository.setIdentityTag(restoredIdentity.shortUserId)
        val summary = BackupSummary(
            userId = restoredIdentity.userId,
            networkCount = payload.networks.size,
            contactCount = payload.members.size,
            blockedCount = payload.blockedUsers.size,
            mutedCount = payload.mutedUsers.size,
            sizeBytes = fileBytes.size
        )
        logRepository.success(
            LogCategory.CRYPTO,
            "Encrypted backup restored: uid=${restoredIdentity.shortUserId} nets=${summary.networkCount} contacts=${summary.contactCount} blocked=${summary.blockedCount} muted=${summary.mutedCount}"
        )
        return restoredIdentity to summary
    }
}
