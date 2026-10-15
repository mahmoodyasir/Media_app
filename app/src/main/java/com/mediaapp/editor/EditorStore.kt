package com.mediaapp.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mediaapp.data.media.StoreAction
import com.mediaapp.data.media.userConsentSender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EditorStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun saveImage(bitmap: Bitmap, displayName: String): Uri {
        val name = jpegName(displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Media App/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the photo")
        writeImage(uri, bitmap)
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    fun overwriteImage(uri: String, bitmap: Bitmap): StoreAction = try {
        writeImage(Uri.parse(uri), bitmap)
        StoreAction.Done("Replaced the original photo")
    } catch (error: SecurityException) {
        val sender = error.userConsentSender()
        if (sender != null) StoreAction.NeedsConsent("overwrite-image", sender)
        else StoreAction.Failed(error.message ?: "Android blocked replacing that photo")
    } catch (error: Exception) {
        StoreAction.Failed(error.message ?: "Couldn't replace that photo")
    }

    fun saveVideo(file: File, displayName: String): Uri {
        val name = mp4Name(displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Media App/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the video")
        writeFile(uri, file)
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    fun overwriteVideo(uri: String, file: File): StoreAction = try {
        writeFile(Uri.parse(uri), file)
        StoreAction.Done("Replaced the original video")
    } catch (error: SecurityException) {
        val sender = error.userConsentSender()
        if (sender != null) StoreAction.NeedsConsent("overwrite-video", sender)
        else StoreAction.Failed(error.message ?: "Android blocked replacing that video")
    } catch (error: Exception) {
        StoreAction.Failed(error.message ?: "Couldn't replace that video")
    }

    private fun writeImage(uri: Uri, bitmap: Bitmap) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) error("Couldn't encode the photo")
        } ?: error("Couldn't open the photo")
    }

    private fun writeFile(uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: error("Couldn't open the video")
    }

    private fun jpegName(displayName: String): String {
        val base = displayName.substringBeforeLast('.').ifBlank { "Photo" }
        return "Edited $base ${System.currentTimeMillis()}.jpg"
    }

    private fun mp4Name(displayName: String): String {
        val base = displayName.substringBeforeLast('.').ifBlank { "Video" }
        return "Edited $base ${System.currentTimeMillis()}.mp4"
    }
}
