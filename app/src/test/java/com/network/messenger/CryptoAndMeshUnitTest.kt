package com.network.messenger

import com.network.messenger.crypto.CryptoManager
import com.network.messenger.crypto.KeyStorage
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CryptoAndMeshUnitTest {

    private class InMemoryKeyStorage : KeyStorage {
        private val map = mutableMapOf<String, ByteArray>()
        override fun saveSecret(alias: String, bytes: ByteArray) {
            map[alias] = bytes.copyOf()
        }

        override fun loadSecret(alias: String): ByteArray? = map[alias]?.copyOf()

        override fun wrapBytes(raw: ByteArray): String = CryptoManager.encodeB64(raw)

        override fun unwrapBytes(wrapped: String): ByteArray = CryptoManager.decodeB64(wrapped)
    }

    @Test
    fun `identity keys sign verify and derive matching user id`() {
        val crypto = CryptoManager(InMemoryKeyStorage())
        val id = crypto.getOrCreateIdentity()
        assertEquals(64, id.userId.length)
        assertEquals(12, id.shortUserId.length)
        assertTrue(crypto.verifySenderMatchesKeys(id.userId, id.ed25519PublicKey, id.x25519PublicKey))

        val msg = "canonical-mesh-packet-bytes".toByteArray()
        val sig = crypto.sign(msg)
        assertTrue(crypto.verify(id.ed25519PublicKey, sig, msg))
        assertFalse(crypto.verify(id.ed25519PublicKey, sig, "tampered".toByteArray()))
    }

    @Test
    fun `pairwise X25519 HKDF key exchange and group AES-256-GCM encryption work and enforce rotation`() {
        val alice = CryptoManager(InMemoryKeyStorage())
        val bob = CryptoManager(InMemoryKeyStorage())
        val aliceId = alice.getOrCreateIdentity()
        val bobId = bob.getOrCreateIdentity()
        assertNotEquals(aliceId.userId, bobId.userId)

        val networkId = alice.generateNetworkId()
        val groupKeyV1 = alice.generateNetworkKey()
        val aad = "pkt1|$networkId|${aliceId.userId}|KEY_EXCHANGE_RESP|1".toByteArray()

        // Alice wraps groupKeyV1 for Bob using X25519 + HKDF-SHA256 + AES-256-GCM
        val wrapped = alice.encryptPairwise(bobId.x25519PublicKey, networkId, groupKeyV1, aad)
        val unwrappedByBob = bob.decryptPairwise(aliceId.x25519PublicKey, networkId, wrapped, aad)
        assertArrayEquals(groupKeyV1, unwrappedByBob)

        // Group encryption & AAD check
        val msgPlain = "mesh hello".toByteArray()
        val msgAad = "m1|$networkId|${aliceId.userId}|TEXT|1".toByteArray()
        val ct = alice.encryptGroupPayload(groupKeyV1, msgPlain, msgAad)
        assertArrayEquals(msgPlain, bob.decryptGroupPayload(unwrappedByBob, ct, msgAad))

        // Key rotation: Alice generates groupKeyV2; Bob with old groupKeyV1 must fail decryption
        val groupKeyV2 = alice.generateNetworkKey()
        val msgAadV2 = "m2|$networkId|${aliceId.userId}|TEXT|2".toByteArray()
        val ctV2 = alice.encryptGroupPayload(groupKeyV2, "after-removal".toByteArray(), msgAadV2)
        var decryptFailed = false
        try {
            bob.decryptGroupPayload(groupKeyV1, ctV2, msgAadV2)
        } catch (_: GeneralSecurityException) {
            decryptFailed = true
        }
        assertTrue("Removed member with v1 key must not decrypt v2 ciphertext", decryptFailed)
    }

    @Test
    fun `log repository preserves pinned app identity header on init and clear`() {
        val logRepo = LogRepository()
        logRepo.setIdentityTag("abcdef012345")
        logRepo.info(LogCategory.TRANSPORT, "Started")
        val first = logRepo.entries.value.first()
        assertTrue(first.isHeader)
        assertTrue(first.message.startsWith("Network messenger v1.0.0 (com.network.messenger)"))

        logRepo.clear()
        val afterClearFirst = logRepo.entries.value.first()
        assertTrue(afterClearFirst.isHeader)
        assertTrue(afterClearFirst.message.startsWith("Network messenger v1.0.0 (com.network.messenger)"))
    }

    @Test
    fun `compact recent logs returns latest 8 entries while background logging continues`() {
        val logRepo = LogRepository()
        repeat(15) { idx ->
            logRepo.info(LogCategory.MESSAGE, "Event #$idx")
        }
        val state = com.network.messenger.ui.contract.MeshUiState(
            logs = logRepo.entries.value,
            showLogsPanel = false
        )
        assertFalse(state.showLogsPanel)
        assertEquals(16, state.logs.size)
        assertEquals(8, state.compactRecentLogs.size)
        assertEquals("Event #7", state.compactRecentLogs.first().message)
        assertEquals("Event #14", state.compactRecentLogs.last().message)
    }
}
