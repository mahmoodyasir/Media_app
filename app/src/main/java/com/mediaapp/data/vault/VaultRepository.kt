package com.mediaapp.data.vault

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import com.mediaapp.data.media.StoreAction
import com.mediaapp.data.media.safeRelativePath
import com.mediaapp.data.media.uniqueFile
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.WatchedFolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: AndroidVaultCipher,
) {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(48) {}
    private val jobs = HashMap<String, LockJob>()
    private val _index = MutableStateFlow(VaultIndex())
    val index: StateFlow<VaultIndex> = _index.asStateFlow()
    private val _suggestions = MutableStateFlow<List<GalleryEntry>>(emptyList())
    val suggestions: StateFlow<List<GalleryEntry>> = _suggestions.asStateFlow()
    private val _progress = MutableStateFlow<LockProgress?>(null)
    val progress: StateFlow<LockProgress?> = _progress.asStateFlow()
    private var loaded = false

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    fun committedEntries(): List<GalleryEntry> =
        _index.value.records.filter { it.state == VaultItemState.COMMITTED }.map { it.toEntry() }

    fun updateSuggestions(items: List<GalleryEntry>) {
        val keys = _index.value.watched.map { it.key }.toSet()
        val stagedUris = _index.value.records
            .filter { it.state == VaultItemState.STAGED }
            .mapNotNull { it.originalUri }
            .toSet()
        _suggestions.value = if (keys.isEmpty()) {
            emptyList()
        } else {
            items.filter { it.folderKey in keys && it.uri !in stagedUris && !it.vault }
        }
    }

    suspend fun lock(entries: List<GalleryEntry>, watch: WatchedFolder?): StoreAction = withContext(Dispatchers.IO) {
        val targets = entries.filter { !it.vault && it.uri.startsWith("content:") }
        if (targets.isEmpty()) return@withContext StoreAction.Failed("Choose photos or videos from the library")
        val stagedIds = mutableListOf<String>()
        try {
            mutex.withLock { loadLocked() }
            targets.forEachIndexed { index, entry ->
                _progress.value = LockProgress(index, targets.size, entry.displayName)
                stagedIds += stagedId(entry)
                stage(entry)
            }
            _progress.value = LockProgress(targets.size, targets.size, "Removing originals")
            if (watch != null) {
                mutex.withLock {
                    val current = _index.value
                    val watched = current.watched.filterNot { it.key == watch.key } + watch
                    persist(current.copy(watched = watched))
                }
            }
            requestDelete(targets.map { it.id to it.uri }, targets.map { stagedId(it) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            rollback(stagedIds)
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "lock failed", error)
            rollback(stagedIds)
            StoreAction.Failed(error.message ?: "Couldn't lock these items")
        } finally {
            _progress.value = null
        }
    }

    suspend fun resumePending(): StoreAction = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
        val staged = _index.value.records.filter { it.state == VaultItemState.STAGED && !it.originalUri.isNullOrBlank() }
        if (staged.isEmpty()) return@withContext StoreAction.Done("Nothing waiting")
        val stillThere = staged.filter { stillExists(it.originalUri) }
        val gone = staged.filter { it !in stillThere }
        if (gone.isNotEmpty()) {
            mutex.withLock {
                val ids = gone.map { it.id }.toSet()
                val current = _index.value
                persist(current.copy(records = current.records.map {
                    if (it.id in ids) it.copy(state = VaultItemState.COMMITTED, originalUri = null) else it
                }))
            }
        }
        if (stillThere.isEmpty()) return@withContext StoreAction.Done("Lock finished")
        requestDelete(stillThere.map { it.id to it.originalUri.orEmpty() }, stillThere.map { it.id })
    }

    suspend fun onConsent(token: String, accepted: Boolean): StoreAction = withContext(Dispatchers.IO) {
        val job = jobs.remove(token) ?: return@withContext StoreAction.Failed("That request expired")
        if (!accepted) {
            rollback(job.recordIds)
            return@withContext StoreAction.Failed("Originals were kept. Nothing was locked.")
        }
        mutex.withLock {
            loadLocked()
            val ids = job.recordIds.toSet()
            val current = _index.value
            persist(current.copy(records = current.records.map { record ->
                if (record.id in ids) record.copy(state = VaultItemState.COMMITTED, originalUri = null) else record
            }))
        }
        StoreAction.Done(
            if (job.recordIds.size == 1) "Locked and hidden from other apps" else "Locked ${job.recordIds.size} items",
        )
    }

    suspend fun restore(entryIds: List<String>): StoreAction = withContext(Dispatchers.IO) {
        val ids = entryIds.map { vaultIdOf(it) }
        val failures = mutableListOf<String>()
        mutex.withLock { loadLocked() }
        ids.forEach { id ->
            val record = _index.value.records.firstOrNull { it.id == id && it.state == VaultItemState.COMMITTED }
            if (record == null) {
                failures += id
                return@forEach
            }
            try {
                writeBack(record)
                mediaFile(id).delete()
                thumbFile(id).delete()
                cache.remove(id)
                mutex.withLock {
                    val current = _index.value
                    persist(current.copy(records = current.records.filterNot { it.id == id }))
                }
            } catch (error: Exception) {
                Log.e(TAG, "restore $id", error)
                failures += record.displayName
            }
        }
        if (failures.isEmpty()) StoreAction.Done("Restored to the gallery")
        else StoreAction.Failed("Couldn't restore ${failures.joinToString()}")
    }

    suspend fun destroy(entryIds: List<String>) = withContext(Dispatchers.IO) {
        val ids = entryIds.map { vaultIdOf(it) }.toSet()
        mutex.withLock {
            loadLocked()
            ids.forEach {
                mediaFile(it).delete()
                thumbFile(it).delete()
                cache.remove(it)
            }
            val current = _index.value
            persist(current.copy(records = current.records.filterNot { it.id in ids }))
        }
    }

    suspend fun importFile(
        source: File,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        width: Int,
        height: Int,
        durationMs: Long,
    ): StoreAction = withContext(Dispatchers.IO) {
        val id = "edit-" + java.util.UUID.randomUUID().toString()
        encryptInto(id, source)
        writeThumb(id, source, isVideo)
        val record = VaultRecord(
            id = id,
            state = VaultItemState.COMMITTED,
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            relativePath = safeRelativePath(null, isVideo),
            bucketName = "Edited",
            sourceId = "edited",
            sourceLabel = "Edited",
            sourceKnown = true,
            folderKey = "edited",
            dateMillis = System.currentTimeMillis(),
            sizeBytes = source.length(),
            width = width,
            height = height,
            durationMs = durationMs,
            lockedAtMillis = System.currentTimeMillis(),
            originalUri = null,
            mediaStoreId = null,
        )
        mutex.withLock {
            loadLocked()
            persist(_index.value.copy(records = _index.value.records + record))
        }
        StoreAction.Done("Saved into Locked")
    }

    suspend fun replaceFile(
        entryId: String,
        source: File,
        width: Int,
        height: Int,
        durationMs: Long,
    ): StoreAction = withContext(Dispatchers.IO) {
        val id = vaultIdOf(entryId)
        mutex.withLock { loadLocked() }
        val record = _index.value.records.firstOrNull { it.id == id && it.state == VaultItemState.COMMITTED }
            ?: return@withContext StoreAction.Failed("That locked item is gone")
        encryptInto(id, source)
        writeThumb(id, source, record.isVideo)
        cache.remove(id)
        mutex.withLock {
            loadLocked()
            val current = _index.value
            persist(current.copy(records = current.records.map {
                if (it.id == id) it.copy(
                    sizeBytes = source.length(),
                    width = width,
                    height = height,
                    durationMs = durationMs,
                ) else it
            }))
        }
        StoreAction.Done("Replaced the locked original")
    }

    fun materialize(entryId: String): File {
        val id = vaultIdOf(entryId)
        val dest = File(context.cacheDir, "editor/$id.media")
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { cipher.crypto().decryptTo(mediaFile(id), it, id) }
        return dest
    }

    fun deleteMaterialized(entryId: String) {
        val id = vaultIdOf(entryId)
        File(context.cacheDir, "editor/$id.media").delete()
    }

    suspend fun forgetWatch(folderKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val current = _index.value
            persist(current.copy(watched = current.watched.filterNot { it.key == folderKey }))
        }
    }

    fun loadThumb(entryId: String): Bitmap? {
        val id = vaultIdOf(entryId)
        cache.get(id)?.let { return it }
        val file = thumbFile(id)
        if (!file.exists()) return null
        return runCatching {
            val bytes = cipher.crypto().decryptBytes(file.readBytes(), "$id.thumb")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.also { cache.put(id, it) }
    }

    fun decodeImage(entryId: String, maxSide: Int): Bitmap? {
        val id = vaultIdOf(entryId)
        val record = _index.value.records.firstOrNull { it.id == id } ?: return null
        val file = mediaFile(id)
        if (!file.exists()) return null
        return runCatching {
            if (record.sizeBytes > MAX_IN_MEMORY) {
                val temp = File.createTempFile("vault_", ".img", context.cacheDir)
                try {
                    FileOutputStream(temp).use { cipher.crypto().decryptTo(file, it, id) }
                    decodeFile(temp.absolutePath, maxSide)
                } finally {
                    temp.delete()
                }
            } else {
                val bytes = cipher.crypto().decryptBytes(file.readBytes(), id)
                decodeBytes(bytes, maxSide)
            }
        }.getOrNull()
    }

    fun prepareShareFile(entry: GalleryEntry): File {
        val id = vaultIdOf(entry.id)
        val dir = File(context.cacheDir, "share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val dest = uniqueFile(dir, entry.displayName)
        FileOutputStream(dest).use { cipher.crypto().decryptTo(mediaFile(id), it, id) }
        return dest
    }

    fun clearShareCache() {
        File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
    }

    fun mediaFile(id: String): File {
        val raw = vaultIdOf(id)
        return File(root(), "media/$raw.vault").also { it.parentFile?.mkdirs() }
    }

    private suspend fun stage(entry: GalleryEntry) {
        val id = stagedId(entry)
        val uri = Uri.parse(entry.uri)
        val destination = mediaFile(id)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output -> cipher.crypto().encrypt(input, output, id) }
        } ?: error("Couldn't read ${entry.displayName}")
        cipher.crypto().openDecryptingChannel(destination, id).use { channel ->
            if (entry.sizeBytes > 0 && channel.size() <= 0L) error("Encryption check failed for ${entry.displayName}")
        }
        runCatching { createThumb(uri, entry.isVideo) }.getOrNull()?.let { bytes ->
            FileOutputStream(thumbFile(id)).use { output ->
                cipher.crypto().encrypt(bytes.inputStream(), output, "$id.thumb")
            }
        }
        val record = VaultRecord(
            id = id,
            state = VaultItemState.STAGED,
            displayName = entry.displayName,
            mimeType = entry.mimeType,
            isVideo = entry.isVideo,
            relativePath = entry.relativePath,
            bucketName = entry.bucketName,
            sourceId = entry.sourceId,
            sourceLabel = entry.sourceLabel,
            sourceKnown = entry.sourceKnown,
            folderKey = entry.folderKey,
            dateMillis = entry.dateMillis,
            sizeBytes = entry.sizeBytes,
            width = entry.width,
            height = entry.height,
            durationMs = entry.durationMs,
            lockedAtMillis = System.currentTimeMillis(),
            originalUri = entry.uri,
            mediaStoreId = entry.mediaStoreId,
        )
        mutex.withLock {
            loadLocked()
            val current = _index.value
            persist(current.copy(records = current.records.filterNot { it.id == id } + record))
        }
    }

    private fun encryptInto(id: String, source: File) {
        val destination = mediaFile(id)
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> cipher.crypto().encrypt(input, output, id) }
        }
    }

    private fun writeThumb(id: String, source: File, isVideo: Boolean) {
        val bitmap = if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                retriever.getFrameAtTime(0)
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        } else {
            decodeFile(source.absolutePath, 480)
        } ?: return
        val scaled = scaleDown(bitmap, 480)
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
        if (scaled !== bitmap) bitmap.recycle()
        FileOutputStream(thumbFile(id)).use { output ->
            cipher.crypto().encrypt(bytes.toByteArray().inputStream(), output, "$id.thumb")
        }
    }

    private fun stagedId(entry: GalleryEntry): String = "lock-" + entry.id.replace(':', '-')

    private fun requestDelete(pairs: List<Pair<String, String>>, recordIds: List<String>): StoreAction {
        val uris = pairs.map { Uri.parse(it.second) }
        if (uris.isEmpty()) return StoreAction.Failed("Nothing to remove")
        if (Build.VERSION.SDK_INT >= 30) {
            val token = java.util.UUID.randomUUID().toString()
            val sender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            jobs[token] = LockJob(recordIds)
            return StoreAction.NeedsConsent(token, sender)
        }
        val deleted = mutableListOf<String>()
        pairs.forEach { (recordId, uri) ->
            try {
                context.contentResolver.delete(Uri.parse(uri), null, null)
                deleted += recordId
            } catch (error: Exception) {
                Log.e(TAG, "delete $uri", error)
            }
        }
        if (deleted.isEmpty()) {
            return StoreAction.Failed("Couldn't remove the originals. Locked copies were not kept.")
                .also { rollback(recordIds) }
        }
        val deletedSet = deleted.toSet()
        runBlockingCommit(recordIds.filter { it in deletedSet })
        val failed = recordIds.filter { it !in deletedSet }
        if (failed.isNotEmpty()) rollback(failed)
        return StoreAction.Done("Locked ${deleted.size} items")
    }

    private fun runBlockingCommit(ids: List<String>) {
        val set = ids.toSet()
        val current = _index.value
        val updated = current.copy(records = current.records.map {
            if (it.id in set) it.copy(state = VaultItemState.COMMITTED, originalUri = null) else it
        })
        persist(updated)
    }

    private fun rollback(ids: List<String>) {
        val set = ids.toSet()
        set.forEach {
            mediaFile(it).delete()
            thumbFile(it).delete()
        }
        val current = _index.value
        persist(current.copy(records = current.records.filterNot { it.id in set && it.state == VaultItemState.STAGED }))
    }

    private fun writeBack(record: VaultRecord) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (record.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, record.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, record.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, safeRelativePath(record.relativePath, record.isVideo))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Couldn't create ${record.displayName}")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    cipher.crypto().decryptTo(mediaFile(record.id), output, record.id)
                } ?: error("Couldn't write ${record.displayName}")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (thrown: Throwable) {
                resolver.delete(uri, null, null)
                throw thrown
            }
        } else {
            val dir = File(Environment.getExternalStorageDirectory(), safeRelativePath(record.relativePath, record.isVideo))
            val dest = uniqueFile(dir, record.displayName)
            FileOutputStream(dest).use { cipher.crypto().decryptTo(mediaFile(record.id), it, record.id) }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(record.mimeType), null)
        }
    }

    private fun stillExists(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return try {
            context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        } catch (_: SecurityException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun createThumb(uri: Uri, isVideo: Boolean): ByteArray? {
        val bitmap = if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } else {
            decodeUri(uri, 480)
        } ?: return null
        val scaled = scaleDown(bitmap, 480)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        return out.toByteArray()
    }

    private fun decodeUri(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeFile(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (width / sample > maxSide * 2 || height / sample > maxSide * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / largest
        val next = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return next
    }

    private fun loadLocked() {
        if (loaded) return
        val file = indexFile()
        val parsed = if (!file.exists() || file.length() == 0L) {
            VaultIndex()
        } else {
            runCatching {
                val json = cipher.crypto().decryptBytes(file.readBytes(), INDEX_AAD).toString(Charsets.UTF_8)
                parseIndex(json)
            }.getOrElse {
                Log.e(TAG, "index unreadable", it)
                VaultIndex()
            }
        }
        val reconciled = parsed.records.map { record ->
            if (record.state == VaultItemState.STAGED && !stillExists(record.originalUri) && mediaFile(record.id).exists()) {
                record.copy(state = VaultItemState.COMMITTED, originalUri = null)
            } else {
                record
            }
        }
        _index.value = parsed.copy(records = reconciled)
        if (reconciled != parsed.records) persist(_index.value)
        loaded = true
    }

    private fun persist(index: VaultIndex) {
        val bytes = cipher.crypto().encryptBytes(encode(index).toByteArray(Charsets.UTF_8), INDEX_AAD)
        val file = indexFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        _index.value = index
    }

    private fun encode(index: VaultIndex): String {
        val records = JSONArray()
        index.records.forEach { record ->
            records.put(JSONObject().apply {
                put("id", record.id)
                put("state", record.state.name)
                put("displayName", record.displayName)
                put("mimeType", record.mimeType)
                put("isVideo", record.isVideo)
                put("relativePath", record.relativePath)
                put("bucketName", record.bucketName)
                put("sourceId", record.sourceId)
                put("sourceLabel", record.sourceLabel)
                put("sourceKnown", record.sourceKnown)
                put("folderKey", record.folderKey)
                put("dateMillis", record.dateMillis)
                put("sizeBytes", record.sizeBytes)
                put("width", record.width)
                put("height", record.height)
                put("durationMs", record.durationMs)
                put("lockedAtMillis", record.lockedAtMillis)
                put("originalUri", record.originalUri)
                put("mediaStoreId", record.mediaStoreId)
            })
        }
        val watched = JSONArray()
        index.watched.forEach { folder ->
            watched.put(JSONObject().apply {
                put("key", folder.key)
                put("title", folder.title)
            })
        }
        return JSONObject().apply {
            put("records", records)
            put("watched", watched)
        }.toString()
    }

    private fun parseIndex(json: String): VaultIndex {
        val root = JSONObject(json)
        val recordsJson = root.optJSONArray("records") ?: JSONArray()
        val watchedJson = root.optJSONArray("watched") ?: JSONArray()
        val records = buildList {
            for (i in 0 until recordsJson.length()) {
                val item = recordsJson.getJSONObject(i)
                add(
                    VaultRecord(
                        id = item.getString("id"),
                        state = runCatching { VaultItemState.valueOf(item.getString("state")) }.getOrDefault(VaultItemState.COMMITTED),
                        displayName = item.optString("displayName", "media"),
                        mimeType = item.optString("mimeType", "application/octet-stream"),
                        isVideo = item.optBoolean("isVideo"),
                        relativePath = item.optString("relativePath").ifBlank { null },
                        bucketName = item.optString("bucketName").ifBlank { null },
                        sourceId = item.optString("sourceId"),
                        sourceLabel = item.optString("sourceLabel", "Folder"),
                        sourceKnown = item.optBoolean("sourceKnown"),
                        folderKey = item.optString("folderKey"),
                        dateMillis = item.optLong("dateMillis"),
                        sizeBytes = item.optLong("sizeBytes"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        durationMs = item.optLong("durationMs"),
                        lockedAtMillis = item.optLong("lockedAtMillis"),
                        originalUri = item.optString("originalUri").ifBlank { null },
                        mediaStoreId = item.optLong("mediaStoreId").takeIf { it > 0L },
                    ),
                )
            }
        }
        val watched = buildList {
            for (i in 0 until watchedJson.length()) {
                val item = watchedJson.getJSONObject(i)
                add(WatchedFolder(item.getString("key"), item.optString("title", "Folder")))
            }
        }
        return VaultIndex(records, watched)
    }

    private fun thumbFile(id: String): File = File(root(), "thumbs/$id.thumb").also { it.parentFile?.mkdirs() }

    private fun indexFile(): File = File(root(), "index.bin")

    private fun root(): File = File(context.filesDir, "vault").apply { mkdirs() }

    private data class LockJob(val recordIds: List<String>)

    private companion object {
        const val TAG = "VaultRepository"
        const val INDEX_AAD = "mediaapp.vault.index.v1"
        const val MAX_IN_MEMORY = 24L * 1024L * 1024L
    }
}
