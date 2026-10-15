package com.mediaapp.data.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Same streaming round trip as the JVM unit test, runnable on a device.
 * Keystore wrapping stays out of this test so it does not need a user-auth prompt.
 */
@RunWith(AndroidJUnit4::class)
class VaultCryptoInstrumentedTest {
    @Test
    fun roundTripAndRejectsWrongAssociatedData() {
        val plain = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val marker = "UNIQUE_MARKER_STRING".toByteArray()
        marker.copyInto(plain, destinationOffset = 100_000)
        val crypto = VaultCrypto(streamingAead)
        val aad = "instrumented-item"
        val cipher = crypto.encryptBytes(plain, aad)
        assertFalse(String(cipher, Charsets.ISO_8859_1).contains("UNIQUE_MARKER_STRING"))
        assertArrayEquals(plain, crypto.decryptBytes(cipher, aad))

        val file = File.createTempFile("vault-instrumented", ".bin")
        file.outputStream().use { crypto.encrypt(plain.inputStream(), it, aad) }
        crypto.openDecryptingChannel(file, aad).use { channel ->
            channel.position(100_000)
            val found = ByteArray(marker.size)
            val buffer = java.nio.ByteBuffer.wrap(found)
            var filled = 0
            while (filled < found.size) {
                val read = channel.read(buffer)
                check(read > 0)
                filled += read
            }
            assertArrayEquals(marker, found)
        }
        file.delete()
        assertThrows(Exception::class.java) { crypto.decryptBytes(cipher, "other-item") }
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
