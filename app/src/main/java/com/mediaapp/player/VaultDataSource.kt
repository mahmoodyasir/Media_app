package com.mediaapp.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mediaapp.data.vault.VaultCrypto
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

const val VAULT_SCHEME = "vault"

@UnstableApi
class VaultDataSource(
    private val openFile: (String) -> File,
    private val crypto: () -> VaultCrypto,
) : BaseDataSource(false) {
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Missing vault id")
        val file = openFile(id)
        if (!file.exists()) throw IOException("Locked item is missing")
        val opened = crypto().openDecryptingChannel(file, id)
        val size = opened.size()
        if (dataSpec.position > size) {
            opened.close()
            throw IOException("Position past end")
        }
        opened.position(dataSpec.position)
        channel = opened
        uri = dataSpec.uri
        val available = size - dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytes = ByteBuffer.wrap(buffer, offset, toRead)
        val read = channel?.read(bytes) ?: return C.RESULT_END_OF_INPUT
        if (read < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        channel?.close()
        channel = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory(
        private val openFile: (String) -> File,
        private val crypto: () -> VaultCrypto,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(openFile, crypto)
    }
}

@UnstableApi
class RoutingDataSource(
    private val base: DataSource,
    private val vault: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        base.addTransferListener(transferListener)
        vault.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (dataSpec.uri.scheme == VAULT_SCHEME) vault else base
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(
        private val baseFactory: DataSource.Factory,
        private val vaultFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(baseFactory.createDataSource(), vaultFactory.createDataSource())
    }
}
