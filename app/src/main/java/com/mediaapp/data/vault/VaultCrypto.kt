package com.mediaapp.data.vault

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * AES-256-GCM streaming encryption (Tink AES256_GCM_HKDF_4KB).
 * Associated data binds ciphertext to an item id, so files cannot be swapped.
 * This type has no Android dependency and is covered by JVM unit tests.
 */
class VaultCrypto(
    private val streamingAead: StreamingAead,
) {
    fun encrypt(input: InputStream, output: OutputStream, aad: String) {
        streamingAead.newEncryptingStream(output, aad.toByteArray(Charsets.UTF_8)).use { encrypted ->
            input.copyTo(encrypted, bufferSize = DEFAULT_BUFFER)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, aad: String) {
        streamingAead.newDecryptingStream(input, aad.toByteArray(Charsets.UTF_8)).use { plain ->
            plain.copyTo(output, bufferSize = DEFAULT_BUFFER)
        }
    }

    fun encryptBytes(plain: ByteArray, aad: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        encrypt(plain.inputStream(), out, aad)
        return out.toByteArray()
    }

    fun decryptBytes(cipher: ByteArray, aad: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        decrypt(cipher.inputStream(), out, aad)
        return out.toByteArray()
    }

    fun decryptTo(cipherFile: File, output: OutputStream, aad: String) {
        openDecryptingChannel(cipherFile, aad).use { channel ->
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER)
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                output.write(buffer.array(), 0, read)
            }
        }
    }

    /**
     * Random-access plaintext. ExoPlayer reopens this at [java.nio.channels.SeekableByteChannel.position]
     * on seek, so video playback never writes a plaintext temp file.
     */
    fun openDecryptingChannel(cipherFile: File, aad: String): SeekableByteChannel {
        val source = FileChannel.open(cipherFile.toPath(), StandardOpenOption.READ)
        return try {
            streamingAead.newSeekableDecryptingChannel(source, aad.toByteArray(Charsets.UTF_8))
        } catch (thrown: Throwable) {
            source.close()
            throw thrown
        }
    }

    private companion object {
        const val DEFAULT_BUFFER = 1024 * 256
    }
}
