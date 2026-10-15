package com.mediaapp.data.model

import com.mediaapp.data.media.MediaDates
import com.mediaapp.data.source.KnownSourceOrder
import java.time.LocalDate
import java.time.ZoneId

enum class MediaFilter { All, Images, Videos }

enum class SortOrder(val label: String) {
    Newest("Newest"),
    Oldest("Oldest"),
    NameAsc("Name A–Z"),
    NameDesc("Name Z–A"),
    Largest("Largest"),
    Smallest("Smallest"),
}

enum class ThemeMode { Dark, Light, System }

data class GalleryEntry(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val relativePath: String?,
    val bucketName: String?,
    val sourceId: String,
    val sourceLabel: String,
    val sourceKnown: Boolean,
    val folderKey: String,
    val mediaStoreId: Long? = null,
    val trashed: Boolean = false,
    val expiresAtMillis: Long? = null,
    val deletedAtMillis: Long? = null,
    val vault: Boolean = false,
)

data class MediaFolder(
    val key: String,
    val title: String,
    val known: Boolean,
    val imageCount: Int,
    val videoCount: Int,
    val coverUri: String?,
    val latestMillis: Long,
    val autoLock: Boolean,
) {
    val total: Int get() = imageCount + videoCount
}

data class WatchedFolder(
    val key: String,
    val title: String,
)

fun List<GalleryEntry>.filtered(filter: MediaFilter): List<GalleryEntry> = when (filter) {
    MediaFilter.All -> this
    MediaFilter.Images -> filter { !it.isVideo }
    MediaFilter.Videos -> filter { it.isVideo }
}

fun List<GalleryEntry>.sortedWith(order: SortOrder): List<GalleryEntry> = when (order) {
    SortOrder.Newest -> sortedByDescending { it.dateMillis }
    SortOrder.Oldest -> sortedBy { it.dateMillis }
    SortOrder.NameAsc -> sortedBy { it.displayName.lowercase() }
    SortOrder.NameDesc -> sortedByDescending { it.displayName.lowercase() }
    SortOrder.Largest -> sortedByDescending { it.sizeBytes }
    SortOrder.Smallest -> sortedBy { it.sizeBytes }
}

fun groupsByDay(order: SortOrder): Boolean =
    order == SortOrder.Newest || order == SortOrder.Oldest

data class DayGroup(
    val date: LocalDate,
    val label: String,
    val items: List<GalleryEntry>,
)

fun List<GalleryEntry>.dayGroups(
    order: SortOrder,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<DayGroup> {
    val grouped = groupBy { MediaDates.day(it.dateMillis, zone) }
    val days = if (order == SortOrder.Oldest) grouped.keys.sorted() else grouped.keys.sortedDescending()
    return days.map { day ->
        DayGroup(
            date = day,
            label = MediaDates.dayLabel(day, today),
            items = grouped.getValue(day),
        )
    }
}

object FolderIndex {
    fun build(items: List<GalleryEntry>, watched: Set<String>): List<MediaFolder> {
        return items.groupBy { it.folderKey }.map { (key, group) ->
            val cover = group.maxByOrNull { it.dateMillis }
            MediaFolder(
                key = key,
                title = cover?.sourceLabel ?: key,
                known = cover?.sourceKnown == true,
                imageCount = group.count { !it.isVideo },
                videoCount = group.count { it.isVideo },
                coverUri = cover?.uri,
                latestMillis = cover?.dateMillis ?: 0L,
                autoLock = key in watched,
            )
        }.sortedWith(compareBy<MediaFolder> { folderRank(it) }.thenBy { it.title.lowercase() })
    }

    private fun folderRank(folder: MediaFolder): Int {
        if (!folder.known) return 10_000
        val index = KnownSourceOrder.indexOf(folder.key)
        return if (index >= 0) index else 9_000
    }
}
