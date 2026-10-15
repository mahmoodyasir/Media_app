package com.mediaapp.data.vault

import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.WatchedFolder

enum class VaultItemState { STAGED, COMMITTED }

data class VaultRecord(
    val id: String,
    val state: VaultItemState,
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
    val lockedAtMillis: Long,
    val originalUri: String?,
    val mediaStoreId: Long?,
)

data class VaultIndex(
    val records: List<VaultRecord> = emptyList(),
    val watched: List<WatchedFolder> = emptyList(),
)

data class LockProgress(val completed: Int, val total: Int, val name: String)

fun VaultRecord.toEntry(): GalleryEntry = GalleryEntry(
    id = "vault:$id",
    uri = "vault://item/$id",
    displayName = displayName,
    mimeType = mimeType,
    isVideo = isVideo,
    dateMillis = dateMillis,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    relativePath = relativePath,
    bucketName = bucketName,
    sourceId = sourceId,
    sourceLabel = sourceLabel,
    sourceKnown = sourceKnown,
    folderKey = folderKey,
    mediaStoreId = mediaStoreId,
    vault = true,
)

fun vaultIdOf(entryId: String): String = entryId.removePrefix("vault:")
