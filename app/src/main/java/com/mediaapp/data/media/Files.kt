package com.mediaapp.data.media

import android.os.Environment
import java.io.File

fun uniqueFile(directory: File, displayName: String): File {
    directory.mkdirs()
    val safe = displayName.replace(Regex("[\\\\/\\u0000]"), "_").ifBlank { "media" }
    var candidate = File(directory, safe)
    if (!candidate.exists()) return candidate
    val dot = safe.lastIndexOf('.')
    val base = if (dot > 0) safe.substring(0, dot) else safe
    val ext = if (dot > 0) safe.substring(dot) else ""
    var index = 1
    while (candidate.exists() && index < 10_000) {
        candidate = File(directory, "$base ($index)$ext")
        index++
    }
    return candidate
}

fun safeRelativePath(path: String?, isVideo: Boolean): String {
    val fallback = if (isVideo) {
        "${Environment.DIRECTORY_MOVIES}/Media App/"
    } else {
        "${Environment.DIRECTORY_PICTURES}/Media App/"
    }
    val cleaned = path?.replace('\\', '/')?.trim()?.trim('/') ?: return fallback
    if (cleaned.isEmpty()) return fallback
    val parts = cleaned.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return fallback
    return cleaned + "/"
}
