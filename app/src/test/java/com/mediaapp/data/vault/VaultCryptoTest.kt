package com.mediaapp.data.vault

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Random

/**
 * JVM round trip for the same streaming primitive the vault uses on device.
 * Android Keystore wrapping is intentionally outside this type so the test
 * can run as a unit test or be copied into an instrumentation suite unchanged.
 */
class VaultCryptoTest {
    private val crypto = VaultCrypto(streamingAead)

    @Test
    fun roundTripBytesAndStream() {
        val plain = ByteArray(2 * 1024 * 1024)
        Random(42).nextBytes(plain)
        val marker = "UNIQUE_MARKER_STRING".toByteArray()
        marker.copyInto(plain, destinationOffset = 900_000)
        val aad = "item-42"

        val cipher = crypto.encryptBytes(plain, aad)
        assertFalse(String(cipher, Charsets.ISO_8859_1).contains("UNIQUE_MARKER_STRING"))
        assertArrayEquals(plain, crypto.decryptBytes(cipher, aad))

        val file = Files.createTempFile("vault-crypto", ".bin").toFile()
        file.outputStream().use { crypto.encrypt(plain.inputStream(), it, aad) }
        val restored = java.io.ByteArrayOutputStream()
        crypto.decryptTo(file, restored, aad)
        assertArrayEquals(plain, restored.toByteArray())

        crypto.openDecryptingChannel(file, aad).use { channel ->
            channel.position(900_000)
            val found = ByteArray(marker.size)
            val buffer = ByteBuffer.wrap(found)
            var filled = 0
            while (filled < found.size) {
                val read = channel.read(buffer)
                assertTrue(read > 0)
                filled += read
            }
            assertArrayEquals(marker, found)
            assertEquals(plain.size.toLong(), channel.size())
        }
        file.delete()
    }

    @Test
    fun wrongAssociatedDataFails() {
        val plain = "secret-frame".toByteArray()
        val cipher = crypto.encryptBytes(plain, "item-a")
        assertThrows(Exception::class.java) {
            crypto.decryptBytes(cipher, "item-b")
        }
    }

    @Test
    fun passcodeHashVerifiesAndRejects() {
        val salt = PasscodeHasher.newSalt()
        val hash = PasscodeHasher.hash("1948", salt, iterations = 10_000)
        assertTrue(PasscodeHasher.verify("1948", salt, hash, iterations = 10_000))
        assertFalse(PasscodeHasher.verify("1949", salt, hash, iterations = 10_000))
    }

    companion object {
        private lateinit var streamingAead: StreamingAead

        @JvmStatic
        @BeforeClass
        fun register() {
            StreamingAeadConfig.register()
            val handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            streamingAead = handle.getPrimitive(StreamingAead::class.java)
        }
    }
}
