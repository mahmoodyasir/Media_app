package com.mediaapp.data.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AccessLevel {
    None,
    Partial,
    Full,
}

/**
 * Permission names kept as literals so this table can be unit tested without the Android SDK.
 * The strings match [android.Manifest.permission].
 */
object MediaPermissions {
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun readPermissions(sdk: Int): Array<String> = when {
        sdk >= 34 -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
        else -> arrayOf(READ_EXTERNAL_STORAGE)
    }

    fun writePermissions(sdk: Int): Array<String> =
        if (sdk <= 28) arrayOf(WRITE_EXTERNAL_STORAGE) else emptyArray()

    fun accessLevel(sdk: Int, granted: Set<String>): AccessLevel = when {
        sdk >= 34 -> when {
            READ_MEDIA_IMAGES in granted && READ_MEDIA_VIDEO in granted -> AccessLevel.Full
            READ_MEDIA_VISUAL_USER_SELECTED in granted ||
                READ_MEDIA_IMAGES in granted ||
                READ_MEDIA_VIDEO in granted -> AccessLevel.Partial
            else -> AccessLevel.None
        }
        sdk >= 33 -> when {
            READ_MEDIA_IMAGES in granted && READ_MEDIA_VIDEO in granted -> AccessLevel.Full
            READ_MEDIA_IMAGES in granted || READ_MEDIA_VIDEO in granted -> AccessLevel.Partial
            else -> AccessLevel.None
        }
        else -> if (READ_EXTERNAL_STORAGE in granted) AccessLevel.Full else AccessLevel.None
    }
}

object MediaDates {
    /** Date taken wins, then date added, then date modified. Added/modified are unix seconds. */
    fun resolve(dateTakenMillis: Long, dateAddedSeconds: Long, dateModifiedSeconds: Long): Long {
        if (dateTakenMillis > 0L) return dateTakenMillis
        if (dateAddedSeconds > 0L) return dateAddedSeconds * 1000L
        if (dateModifiedSeconds > 0L) return dateModifiedSeconds * 1000L
        return 0L
    }

    fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis.coerceAtLeast(0L)).atZone(zone).toLocalDate()

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()))
    }
}

object TrashPolicy {
    const val RETENTION_DAYS = 30
    const val DAY_MS = 86_400_000L

    fun daysRemaining(expiresAtMillis: Long?, deletedAtMillis: Long?, now: Long): Int {
        val expiry = when {
            expiresAtMillis != null && expiresAtMillis > 0L -> expiresAtMillis
            deletedAtMillis != null && deletedAtMillis > 0L -> deletedAtMillis + RETENTION_DAYS * DAY_MS
            else -> now + RETENTION_DAYS * DAY_MS
        }
        val diff = expiry - now
        if (diff <= 0L) return 0
        return ((diff + DAY_MS - 1) / DAY_MS).toInt()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val pattern = if (value >= 10.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unit])
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0L) return "Unknown"
    val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
    return dateTime.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))
}
