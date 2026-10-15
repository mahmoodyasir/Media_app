package com.mediaapp.data.media

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.mediaapp.data.model.GalleryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: MediaStoreReader,
) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<GalleryEntry>>(emptyList())
    val items: StateFlow<List<GalleryEntry>> = _items.asStateFlow()
    private val pending = HashMap<String, Pending>()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            purgeExpiredLocal()
            val system = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { reader.load(trashedOnly = true) }.getOrElse {
                    Log.e(TAG, "system trash query failed", it)
                    emptyList()
                }
            } else {
                emptyList()
            }
            _items.value = system + localEntries()
        }
    }

    suspend fun trash(entries: List<GalleryEntry>): StoreAction = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext StoreAction.Failed("Nothing selected")
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = entries.mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
            if (uris.isEmpty()) return@withContext StoreAction.Failed("Those items can't be moved")
            val token = UUID.randomUUID().toString()
            val sender = MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender
            pending[token] = Pending(kind = Kind.SYSTEM, uris = uris)
            StoreAction.NeedsConsent(token, sender)
        } else {
            trashLegacy(entries)
        }
    }

    suspend fun restore(entries: List<GalleryEntry>): StoreAction = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext StoreAction.Failed("Nothing selected")
        val system = entries.filter { !it.id.startsWith("local:") }
        val local = entries.filter { it.id.startsWith("local:") }
        local.forEach { restoreLocal(it.id) }
        if (system.isEmpty()) {
            refresh()
            return@withContext StoreAction.Done("Restored")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = system.map { Uri.parse(it.uri) }
            val token = UUID.randomUUID().toString()
            val sender = MediaStore.createTrashRequest(context.contentResolver, uris, false).intentSender
            pending[token] = Pending(kind = Kind.SYSTEM, uris = uris)
            StoreAction.NeedsConsent(token, sender)
        } else {
            StoreAction.Failed("Those items aren't in the app recycle bin")
        }
    }

    suspend fun deleteForever(entries: List<GalleryEntry>): StoreAction = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext StoreAction.Failed("Nothing selected")
        val system = entries.filter { !it.id.startsWith("local:") }
        val local = entries.filter { it.id.startsWith("local:") }
        mutex.withLock {
            val index = readIndex().filterNot { record -> local.any { it.id == record.id } }
            local.forEach { recordFile(it.id.removePrefix("local:")).delete() }
            writeIndex(index)
        }
        if (system.isEmpty()) {
            refresh()
            return@withContext StoreAction.Done("Deleted")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = system.map { Uri.parse(it.uri) }
            val token = UUID.randomUUID().toString()
            val sender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            pending[token] = Pending(kind = Kind.SYSTEM, uris = uris)
            StoreAction.NeedsConsent(token, sender)
        } else {
            StoreAction.Done("Deleted")
        }
    }

    suspend fun empty(): StoreAction = deleteForever(_items.value)

    suspend fun onConsent(token: String, accepted: Boolean): StoreAction = withContext(Dispatchers.IO) {
        val job = pending.remove(token) ?: return@withContext StoreAction.Failed("That request expired")
        if (!accepted) {
            if (job.kind == Kind.LOCAL_COPY) rollbackLocal(job.localId)
            return@withContext StoreAction.Failed("Cancelled")
        }
        if (job.kind == Kind.LOCAL_COPY) {
            mutex.withLock {
                val index = readIndex().map { if (it.id == job.localId) it.copy(committed = true) else it }
                writeIndex(index)
            }
        }
        refresh()
        StoreAction.Done("Updated")
    }

    private suspend fun trashLegacy(entries: List<GalleryEntry>): StoreAction {
        var moved = 0
        for (entry in entries) {
            val action = copyThenDelete(entry)
            when (action) {
                is StoreAction.NeedsConsent -> return action
                is StoreAction.Failed -> if (moved == 0 && entries.size == 1) return action
                is StoreAction.Done -> moved++
            }
        }
        refresh()
        return StoreAction.Done(if (moved == 1) "Moved to the recycle bin" else "Moved $moved items to the recycle bin")
    }

    private fun copyThenDelete(entry: GalleryEntry): StoreAction {
        val id = UUID.randomUUID().toString()
        val stored = recordFile(id)
        stored.parentFile?.mkdirs()
        try {
            context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { input ->
                FileOutputStream(stored).use { output -> input.copyTo(output) }
            } ?: return StoreAction.Failed("Couldn't read ${entry.displayName}")
        } catch (error: Exception) {
            stored.delete()
            return StoreAction.Failed(error.message ?: "Couldn't read ${entry.displayName}")
        }
        val record = LocalTrash(
            id = "local:$id",
            fileName = stored.name,
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
            deletedAtMillis = System.currentTimeMillis(),
            originalUri = entry.uri,
            committed = false,
        )
        synchronized(pending) {
            val index = readIndex() + record
            writeIndex(index)
        }
        return try {
            val rows = context.contentResolver.delete(Uri.parse(entry.uri), null, null)
            if (rows <= 0) {
                rollbackLocal(record.id)
                StoreAction.Failed("Couldn't remove ${entry.displayName}")
            } else {
                markCommitted(record.id)
                StoreAction.Done("Moved")
            }
        } catch (error: SecurityException) {
            val sender = error.userConsentSender()
            if (sender != null) {
                val token = UUID.randomUUID().toString()
                pending[token] = Pending(kind = Kind.LOCAL_COPY, localId = record.id, sender = sender)
                return StoreAction.NeedsConsent(token, sender)
            }
            rollbackLocal(record.id)
            StoreAction.Failed(error.message ?: "Couldn't remove ${entry.displayName}")
        }
    }

    private fun markCommitted(id: String) {
        val index = readIndex().map { if (it.id == id) it.copy(committed = true) else it }
        writeIndex(index)
    }

    private fun rollbackLocal(id: String?) {
        if (id == null) return
        val raw = id.removePrefix("local:")
        recordFile(raw).delete()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    private fun restoreLocal(id: String) {
        val record = readIndex().firstOrNull { it.id == id } ?: return
        val source = recordFile(record.id.removePrefix("local:"))
        if (!source.exists()) {
            writeIndex(readIndex().filterNot { it.id == id })
            return
        }
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
            val uri = context.contentResolver.insert(collection, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStorageDirectory(), safeRelativePath(record.relativePath, record.isVideo))
            val dest = uniqueFile(dir, record.displayName)
            source.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(record.mimeType), null)
        }
        source.delete()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    private fun purgeExpiredLocal() {
        val now = System.currentTimeMillis()
        val keep = readIndex().filter { record ->
            val days = TrashPolicy.daysRemaining(null, record.deletedAtMillis, now)
            if (days <= 0 && record.committed) {
                recordFile(record.id.removePrefix("local:")).delete()
                false
            } else {
                true
            }
        }
        writeIndex(keep)
    }

    private fun localEntries(): List<GalleryEntry> {
        return readIndex().filter { it.committed }.map { record ->
            GalleryEntry(
                id = record.id,
                uri = recordFile(record.id.removePrefix("local:")).toURI().toString(),
                displayName = record.displayName,
                mimeType = record.mimeType,
                isVideo = record.isVideo,
                dateMillis = record.dateMillis,
                sizeBytes = record.sizeBytes,
                width = record.width,
                height = record.height,
                durationMs = record.durationMs,
                relativePath = record.relativePath,
                bucketName = record.bucketName,
                sourceId = record.sourceId,
                sourceLabel = record.sourceLabel,
                sourceKnown = record.sourceKnown,
                folderKey = record.folderKey,
                trashed = true,
                deletedAtMillis = record.deletedAtMillis,
            )
        }
    }

    private fun recordFile(rawId: String): File = File(binDir(), "$rawId.bin")

    private fun binDir(): File = File(context.filesDir, "recycle").apply { mkdirs() }

    private fun indexFile(): File = File(binDir(), "index.json")

    private fun readIndex(): List<LocalTrash> {
        val file = indexFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) add(LocalTrash.fromJson(array.getJSONObject(i)))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(records: List<LocalTrash>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        indexFile().writeText(array.toString())
    }

    private enum class Kind { SYSTEM, LOCAL_COPY }

    private data class Pending(
        val kind: Kind,
        val uris: List<Uri> = emptyList(),
        val localId: String? = null,
        val sender: IntentSender? = null,
    )

    private data class LocalTrash(
        val id: String,
        val fileName: String,
        val displayName: String,
        val mimeType: String,
        val isVideo: Boolean,
        val relativePath: String?,
        val bucketName: String?,
        val sourceId: String,
        val sourceLabel: String,
        val sourceKnown: Boolean,
        val folderKey: String,
        val dateMillis: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val deletedAtMillis: Long,
        val originalUri: String?,
        val committed: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("fileName", fileName)
            put("displayName", displayName)
            put("mimeType", mimeType)
            put("isVideo", isVideo)
            put("relativePath", relativePath)
            put("bucketName", bucketName)
            put("sourceId", sourceId)
            put("sourceLabel", sourceLabel)
            put("sourceKnown", sourceKnown)
            put("folderKey", folderKey)
            put("dateMillis", dateMillis)
            put("sizeBytes", sizeBytes)
            put("width", width)
            put("height", height)
            put("durationMs", durationMs)
            put("deletedAtMillis", deletedAtMillis)
            put("originalUri", originalUri)
            put("committed", committed)
        }

        companion object {
            fun fromJson(json: JSONObject) = LocalTrash(
                id = json.getString("id"),
                fileName = json.optString("fileName"),
                displayName = json.optString("displayName", "media"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                isVideo = json.optBoolean("isVideo"),
                relativePath = json.optString("relativePath").ifBlank { null },
                bucketName = json.optString("bucketName").ifBlank { null },
                sourceId = json.optString("sourceId"),
                sourceLabel = json.optString("sourceLabel"),
                sourceKnown = json.optBoolean("sourceKnown"),
                folderKey = json.optString("folderKey"),
                dateMillis = json.optLong("dateMillis"),
                sizeBytes = json.optLong("sizeBytes"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                durationMs = json.optLong("durationMs"),
                deletedAtMillis = json.optLong("deletedAtMillis"),
                originalUri = json.optString("originalUri").ifBlank { null },
                committed = json.optBoolean("committed"),
            )
        }
    }

    private companion object {
        const val TAG = "TrashRepository"
    }
}
