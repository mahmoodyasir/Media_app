package com.mediaapp.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.source.ClassificationInput
import com.mediaapp.data.source.SourceClassifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LibrarySnapshot(
    val items: List<GalleryEntry> = emptyList(),
    val access: AccessLevel = AccessLevel.None,
    val loaded: Boolean = false,
    val error: String? = null,
)

@Singleton
class MediaStoreReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: SourceClassifier,
) {
    fun load(trashedOnly: Boolean): List<GalleryEntry> {
        if (context.mediaAccess() == AccessLevel.None) return emptyList()
        val images = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, trashedOnly)
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, trashedOnly)
        return images + videos
    }

    private fun query(uri: Uri, isVideo: Boolean, trashedOnly: Boolean): List<GalleryEntry> {
        if (trashedOnly && Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            queryOnce(uri, isVideo, trashedOnly, includeData = true)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Retrying media query without DATA", error)
            queryOnce(uri, isVideo, trashedOnly, includeData = false)
        }
    }

    private fun queryOnce(uri: Uri, isVideo: Boolean, trashedOnly: Boolean, includeData: Boolean): List<GalleryEntry> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (includeData) add(MediaStore.MediaColumns.DATA)
            if (isVideo) {
                add(MediaStore.Video.Media.DURATION)
                add(MediaStore.Video.Media.DATE_TAKEN)
            } else {
                add(MediaStore.Images.Media.DATE_TAKEN)
                add(MediaStore.Images.Media.ORIENTATION)
            }
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (trashedOnly && Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.DATE_EXPIRES)
        }.toTypedArray()

        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )
            if (Build.VERSION.SDK_INT >= 29 && !trashedOnly) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_PENDING}=0")
            }
            if (Build.VERSION.SDK_INT >= 30) {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (trashedOnly) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE,
                )
            }
        }
        val cursor = context.contentResolver.query(uri, projection, args, null) ?: return emptyList()
        return cursor.use { read(it, isVideo, trashedOnly) }
    }

    private fun read(cursor: Cursor, isVideo: Boolean, trashed: Boolean): List<GalleryEntry> {
        val items = ArrayList<GalleryEntry>(cursor.count.coerceAtLeast(0))
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        while (cursor.moveToNext()) {
            val mediaId = cursor.long(MediaStore.MediaColumns._ID)
            if (mediaId <= 0L) continue
            val name = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME)?.ifBlank { null } ?: "media"
            val relative = cursor.string(MediaStore.MediaColumns.RELATIVE_PATH)
                ?: parentRelative(cursor.string(MediaStore.MediaColumns.DATA))
            val bucket = cursor.string(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val mime = cursor.string(MediaStore.MediaColumns.MIME_TYPE)
                ?: if (isVideo) "video/*" else "image/*"
            val source = classifier.classify(ClassificationInput(relative, bucket, name, mime))
            var width = cursor.int(MediaStore.MediaColumns.WIDTH)
            var height = cursor.int(MediaStore.MediaColumns.HEIGHT)
            val orientation = if (isVideo) 0 else cursor.int(MediaStore.Images.Media.ORIENTATION)
            if (orientation == 90 || orientation == 270) {
                val swap = width
                width = height
                height = swap
            }
            val takenColumn = if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN
            val resolved = MediaDates.resolve(
                cursor.long(takenColumn),
                cursor.long(MediaStore.MediaColumns.DATE_ADDED),
                cursor.long(MediaStore.MediaColumns.DATE_MODIFIED),
            )
            items += GalleryEntry(
                id = if (isVideo) "video:$mediaId" else "image:$mediaId",
                uri = ContentUris.withAppendedId(collection, mediaId).toString(),
                displayName = name,
                mimeType = mime,
                isVideo = isVideo,
                dateMillis = if (resolved > 0L) resolved else System.currentTimeMillis(),
                sizeBytes = cursor.long(MediaStore.MediaColumns.SIZE),
                width = width,
                height = height,
                durationMs = if (isVideo) cursor.long(MediaStore.Video.Media.DURATION) else 0L,
                relativePath = relative,
                bucketName = bucket,
                sourceId = source.id,
                sourceLabel = source.label,
                sourceKnown = source.known,
                folderKey = source.id,
                mediaStoreId = mediaId,
                trashed = trashed,
                expiresAtMillis = cursor.long(MediaStore.MediaColumns.DATE_EXPIRES).takeIf { it > 0L },
            )
        }
        return items
    }

    private fun parentRelative(absolute: String?): String? {
        if (absolute.isNullOrBlank()) return null
        val parent = File(absolute).parentFile ?: return null
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = parent.absolutePath
        val relative = if (path.startsWith(root)) path.removePrefix(root) else path
        return relative.trim('/') + "/"
    }

    private fun Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun Cursor.int(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private companion object {
        const val TAG = "MediaStoreReader"
    }
}
