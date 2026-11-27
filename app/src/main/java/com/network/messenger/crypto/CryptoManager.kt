package com.network.messenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

data class LocalIdentity(
    val userId: String,
    val shortUserId: String,
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val ed25519PublicBase64: String,
    val x25519PublicBase64: String
)

data class ExportedIdentityKeys(
    val ed25519PrivBase64: String,
    val ed25519PubBase64: String,
    val x25519PrivBase64: String,
    val x25519PubBase64: String
)

/**
 * End-to-end cryptographic engine backed exclusively by Google Tink primitives
 * (Ed25519, X25519, HKDF-SHA256, AES-256-GCM), JCA PBKDF2-HMAC-SHA256 for backup KDF,
 * and Android Keystore for private key storage.
 * Never logs raw keys, passphrases, or decrypted plaintext.
 */
class CryptoManager(
    private val storage: KeyStorage
) {
    private val lock = Any()
    private var cachedEd25519Priv: ByteArray? = null
    private var cachedEd25519Pub: ByteArray? = null
    private var cachedX25519Priv: ByteArray? = null
    private var cachedX25519Pub: ByteArray? = null

    fun getOrCreateIdentity(): LocalIdentity = synchronized(lock) {
        if (cachedEd25519Priv == null || cachedX25519Priv == null) {
            val storedEdPriv = storage.loadSecret(KEY_ED25519_PRIV)
            val storedEdPub = storage.loadSecret(KEY_ED25519_PUB)
            val storedXPriv = storage.loadSecret(KEY_X25519_PRIV)
            val storedXPub = storage.loadSecret(KEY_X25519_PUB)

            if (storedEdPriv != null && storedEdPub != null && storedXPriv != null && storedXPub != null) {
                cachedEd25519Priv = storedEdPriv
                cachedEd25519Pub = storedEdPub
                cachedX25519Priv = storedXPriv
                cachedX25519Pub = storedXPub
            } else {
                val edPair = Ed25519Sign.KeyPair.newKeyPair()
                val xPriv = X25519.generatePrivateKey()
                val xPub = X25519.publicFromPrivate(xPriv)

                storage.saveSecret(KEY_ED25519_PRIV, edPair.privateKey)
                storage.saveSecret(KEY_ED25519_PUB, edPair.publicKey)
                storage.saveSecret(KEY_X25519_PRIV, xPriv)
                storage.saveSecret(KEY_X25519_PUB, xPub)

                cachedEd25519Priv = edPair.privateKey
                cachedEd25519Pub = edPair.publicKey
                cachedX25519Priv = xPriv
                cachedX25519Pub = xPub
            }
        }
        val edPub = cachedEd25519Pub!!
        val xPub = cachedX25519Pub!!
        val fullId = deriveUserId(edPub, xPub)
        LocalIdentity(
            userId = fullId,
            shortUserId = shortId(fullId),
            ed25519PublicKey = edPub.copyOf(),
            x25519PublicKey = xPub.copyOf(),
            ed25519PublicBase64 = encodeB64(edPub),
            x25519PublicBase64 = encodeB64(xPub)
        )
    }

    /**
     * Exports local identity key material strictly for passphrase-encrypted backup generation.
     */
    fun exportIdentityKeysForBackup(): ExportedIdentityKeys = synchronized(lock) {
        getOrCreateIdentity()
        ExportedIdentityKeys(
            ed25519PrivBase64 = encodeB64(cachedEd25519Priv!!),
            ed25519PubBase64 = encodeB64(cachedEd25519Pub!!),
            x25519PrivBase64 = encodeB64(cachedX25519Priv!!),
            x25519PubBase64 = encodeB64(cachedX25519Pub!!)
        )
    }

    /**
     * Restores identity key material from a decrypted backup into Android Keystore-wrapped storage.
     */
    fun importIdentityKeysFromBackup(keys: ExportedIdentityKeys): LocalIdentity = synchronized(lock) {
        val edPriv = decodeB64(keys.ed25519PrivBase64)
        val edPub = decodeB64(keys.ed25519PubBase64)
        val xPriv = decodeB64(keys.x25519PrivBase64)
        val xPub = decodeB64(keys.x25519PubBase64)
        require(edPriv.size == 32 && edPub.size == 32 && xPriv.size == 32 && xPub.size == 32) {
            "Invalid identity key lengths in backup"
        }
        storage.saveSecret(KEY_ED25519_PRIV, edPriv)
        storage.saveSecret(KEY_ED25519_PUB, edPub)
        storage.saveSecret(KEY_X25519_PRIV, xPriv)
        storage.saveSecret(KEY_X25519_PUB, xPub)
        cachedEd25519Priv = edPriv
        cachedEd25519Pub = edPub
        cachedX25519Priv = xPriv
        cachedX25519Pub = xPub
        getOrCreateIdentity()
    }

    fun generateNetworkId(): String = toHex(Random.randBytes(16))

    fun generateNetworkKey(): ByteArray = Random.randBytes(32)

    fun generateRandomBytes(length: Int): ByteArray = Random.randBytes(length)

    fun sign(data: ByteArray): ByteArray {
        getOrCreateIdentity()
        val signer = Ed25519Sign(cachedEd25519Priv!!)
        return signer.sign(data)
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        return try {
            Ed25519Verify(publicKey).verify(signature, data)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun verifySenderMatchesKeys(senderId: String, ed25519Pub: ByteArray, x25519Pub: ByteArray): Boolean {
        val expected = deriveUserId(ed25519Pub, x25519Pub)
        return MessageDigest.isEqual(
            senderId.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Encrypts payload with per-network 256-bit AES-GCM key and binds routing metadata via AAD.
     */
    fun encryptGroupPayload(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val aead = AesGcmJce(key)
        return aead.encrypt(plaintext, aad)
    }

    fun decryptGroupPayload(key: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val aead = AesGcmJce(key)
        return aead.decrypt(ciphertext, aad)
    }

    /**
     * Pairwise X25519 ECDH + HKDF-SHA256 + AES-256-GCM key exchange encryption.
     */
    fun encryptPairwise(
        peerX25519PublicKey: ByteArray,
        networkId: String,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        getOrCreateIdentity()
        val sharedSecret = X25519.computeSharedSecret(cachedX25519Priv!!, peerX25519PublicKey)
        val derivedKey = Hkdf.computeHkdf(
            "HmacSha256",
            sharedSecret,
            networkId.toByteArray(Charsets.UTF_8),
            "network-messenger-pairwise-v1".toByteArray(Charsets.UTF_8),
            32
        )
        return AesGcmJce(derivedKey).encrypt(plaintext, aad)
    }

    fun decryptPairwise(
        peerX25519PublicKey: ByteArray,
        networkId: String,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        getOrCreateIdentity()
        val sharedSecret = X25519.computeSharedSecret(cachedX25519Priv!!, peerX25519PublicKey)
        val derivedKey = Hkdf.computeHkdf(
            "HmacSha256",
            sharedSecret,
            networkId.toByteArray(Charsets.UTF_8),
            "network-messenger-pairwise-v1".toByteArray(Charsets.UTF_8),
            32
        )
        return AesGcmJce(derivedKey).decrypt(ciphertext, aad)
    }

    /**
     * Derives a 32-byte key from an optional network password and salt using HKDF-SHA256.
     */
    fun derivePasswordKey(password: String, salt: ByteArray, networkId: String): ByteArray {
        return Hkdf.computeHkdf(
            "HmacSha256",
            password.toByteArray(Charsets.UTF_8),
            salt,
            "network-messenger-pwd-$networkId".toByteArray(Charsets.UTF_8),
            32
        )
    }

    /**
     * Derives a 256-bit backup encryption key using PBKDF2-HMAC-SHA256 (standard JCA KDF).
     */
    fun deriveBackupKeyPbkdf2(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = BACKUP_PBKDF2_ITERATIONS
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Encrypts backup plaintext with PBKDF2-HMAC-SHA256 + Tink AES-256-GCM.
     */
    fun encryptBackupPayload(
        passphrase: String,
        plaintext: ByteArray,
        iterations: Int = BACKUP_PBKDF2_ITERATIONS
    ): Pair<ByteArray, ByteArray> {
        require(passphrase.length >= 6) { "Backup passphrase must be at least 6 characters" }
        val salt = Random.randBytes(32)
        val key = deriveBackupKeyPbkdf2(passphrase.toCharArray(), salt, iterations)
        val aad = "NMSG_BACKUP_V1|$iterations".toByteArray(Charsets.UTF_8)
        val ciphertext = AesGcmJce(key).encrypt(plaintext, aad)
        key.fill(0)
        return salt to ciphertext
    }

    fun decryptBackupPayload(
        passphrase: String,
        salt: ByteArray,
        ciphertext: ByteArray,
        iterations: Int
    ): ByteArray {
        val key = deriveBackupKeyPbkdf2(passphrase.toCharArray(), salt, iterations)
        return try {
            val aad = "NMSG_BACKUP_V1|$iterations".toByteArray(Charsets.UTF_8)
            AesGcmJce(key).decrypt(ciphertext, aad)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Computes a symmetric 60-digit safety number (12 blocks of 5 digits) identical on both
     * phones for contact key verification.
     */
    fun computeSafetyNumber(peerEd25519PubBase64: String, peerX25519PubBase64: String): String {
        val local = getOrCreateIdentity()
        val localFp = computeKeyFingerprint(local.ed25519PublicBase64, local.x25519PublicBase64)
        val peerFp = computeKeyFingerprint(peerEd25519PubBase64, peerX25519PubBase64)
        val ordered = listOf(localFp, peerFp).sorted()
        val combinedInput = "NMSG_SAFETY_V1|${ordered[0]}|${ordered[1]}".toByteArray(Charsets.UTF_8)

        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest(combinedInput)
        repeat(64) {
            md.reset()
            md.update(digest)
            md.update(combinedInput)
            digest = md.digest()
        }
        // Expand into 12 deterministic 5-digit blocks (60 digits total)
        val blocks = (0 until 12).map { idx ->
            md.reset()
            md.update(digest)
            md.update(idx.toByte())
            val d = md.digest()
            val num = ((d[0].toLong() and 0xFF) shl 24) or
                ((d[1].toLong() and 0xFF) shl 16) or
                ((d[2].toLong() and 0xFF) shl 8) or
                (d[3].toLong() and 0xFF)
            "%05d".format(num % 100_000L)
        }
        return blocks.joinToString(" ")
    }

    fun buildVerificationQrPayload(): String {
        val local = getOrCreateIdentity()
        val fp = computeKeyFingerprint(local.ed25519PublicBase64, local.x25519PublicBase64)
        return "$VERIFY_QR_PREFIX${local.userId}:$fp"
    }

    /**
     * Wraps a per-network symmetric key for secure storage in Android Keystore-backed storage.
     */
    fun wrapForLocalStorage(rawKey: ByteArray): String = storage.wrapBytes(rawKey)

    fun unwrapFromLocalStorage(wrapped: String): ByteArray = storage.unwrapBytes(wrapped)

    companion object {
        private const val KEY_ED25519_PRIV = "id_ed25519_priv"
        private const val KEY_ED25519_PUB = "id_ed25519_pub"
        private const val KEY_X25519_PRIV = "id_x25519_priv"
        private const val KEY_X25519_PUB = "id_x25519_pub"
        const val BACKUP_PBKDF2_ITERATIONS = 210_000
        const val VERIFY_QR_PREFIX = "NMSGV1:"

        fun deriveUserId(ed25519Pub: ByteArray, x25519Pub: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(ed25519Pub)
            md.update(x25519Pub)
            return toHex(md.digest())
        }

        fun computeKeyFingerprint(ed25519PubBase64: String, x25519PubBase64: String): String {
            val ed = runCatching { decodeB64(ed25519PubBase64) }.getOrDefault(ByteArray(0))
            val x = runCatching { decodeB64(x25519PubBase64) }.getOrDefault(ByteArray(0))
            return deriveUserId(ed, x)
        }

        fun parseVerificationQr(raw: String): Pair<String, String>? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(VERIFY_QR_PREFIX)) return null
            val parts = trimmed.removePrefix(VERIFY_QR_PREFIX).split(":")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return parts[0] to parts[1]
        }

        fun shortId(fullId: String): String =
            if (fullId.length <= 12) fullId else fullId.substring(0, 12)

        fun encodeB64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun decodeB64(value: String): ByteArray =
            Base64.decode(value, Base64.NO_WRAP)

        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}

interface KeyStorage {
    fun saveSecret(alias: String, bytes: ByteArray)
    fun loadSecret(alias: String): ByteArray?
    fun wrapBytes(raw: ByteArray): String
    fun unwrapBytes(wrapped: String): ByteArray
}

/**
 * Stores identity and network symmetric keys encrypted by an AES-256-GCM master key
 * residing in Android Keystore.
 */
class AndroidKeystoreKeyStorage(context: Context) : KeyStorage {
    private val prefs = context.getSharedPreferences("nm_secure_keystore_prefs", Context.MODE_PRIVATE)

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun saveSecret(alias: String, bytes: ByteArray) {
        prefs.edit().putString(alias, wrapBytes(bytes)).apply()
    }

    override fun loadSecret(alias: String): ByteArray? {
        val encoded = prefs.getString(alias, null) ?: return null
        return runCatching { unwrapBytes(encoded) }.getOrNull()
    }

    override fun wrapBytes(raw: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(raw)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return CryptoManager.encodeB64(combined)
    }

    override fun unwrapBytes(wrapped: String): ByteArray {
        val combined = CryptoManager.decodeB64(wrapped)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "com.network.messenger.master_key_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
