package com.mediaapp.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediaapp.data.media.AccessLevel
import com.mediaapp.data.media.TrashPolicy
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.MediaFilter
import com.mediaapp.data.model.MediaFolder
import com.mediaapp.data.model.SortOrder
import com.mediaapp.data.model.ThemeMode
import com.mediaapp.data.vault.LockProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    snackbar: SnackbarHostState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onOpenFolder: (String) -> Unit,
    onOpenEntry: (BrowserUi, GalleryEntry) -> Unit,
    showFolders: Boolean = false,
) {
    if (showFolders) {
        FoldersRoute(onOpenFolder)
        return
    }
    val vm: BrowserViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    val requestAccess = rememberMediaPermissionRequest(vm::refresh)
    GalleryBody(
        ui = ui,
        progress = progress,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
        folderScreen = false,
        trashScreen = false,
        onFilter = vm::setFilter,
        onSort = vm::setSort,
        onColumns = vm::setColumns,
        onTheme = vm::setTheme,
        onDynamic = vm::setDynamic,
        onOpen = { onOpenEntry(ui, it) },
        onToggle = vm::toggle,
        onStartSelect = vm::startSelect,
        onClear = vm::clearSelection,
        onSelectAll = vm::selectAll,
        onShare = { vm.share(it, vm.selectedEntries()) },
        onTrash = { vm.trashSelected() },
        onLock = { watch -> vm.lockSelected(watch) },
        onLockFolder = { watch -> vm.lockFolder(watch) },
        onLockSuggestions = vm::lockSuggestions,
        onRequestAccess = requestAccess,
        thumb = { LibraryThumb(it) },
    )
}

@Composable
fun FolderRoute(
    snackbar: SnackbarHostState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onOpenEntry: (BrowserUi, GalleryEntry) -> Unit,
) {
    val vm: BrowserViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    GalleryBody(
        ui = ui,
        progress = progress,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
        folderScreen = true,
        trashScreen = false,
        onFilter = vm::setFilter,
        onSort = vm::setSort,
        onColumns = vm::setColumns,
        onTheme = vm::setTheme,
        onDynamic = vm::setDynamic,
        onOpen = { onOpenEntry(ui, it) },
        onToggle = vm::toggle,
        onStartSelect = vm::startSelect,
        onClear = vm::clearSelection,
        onSelectAll = vm::selectAll,
        onShare = { vm.share(it, vm.selectedEntries()) },
        onTrash = { vm.trashSelected() },
        onLock = { watch -> vm.lockSelected(watch) },
        onLockFolder = { watch -> vm.lockFolder(watch) },
        onLockSuggestions = vm::lockSuggestions,
        onRequestAccess = {},
        thumb = { LibraryThumb(it) },
    )
}

@Composable
fun TrashRoute(
    snackbar: SnackbarHostState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onOpenEntry: (BrowserUi, GalleryEntry) -> Unit,
) {
    val vm: TrashViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    GalleryBody(
        ui = ui,
        progress = null,
        sharedScope = sharedScope,
        animatedScope = animatedScope,
        folderScreen = false,
        trashScreen = true,
        onFilter = vm::setFilter,
        onSort = { _ -> },
        onColumns = vm::setColumns,
        onTheme = { _ -> },
        onDynamic = { _ -> },
        onOpen = { onOpenEntry(ui, it) },
        onToggle = vm::toggle,
        onStartSelect = vm::startSelect,
        onClear = vm::clearSelection,
        onSelectAll = vm::selectAll,
        onShare = { _ -> },
        onTrash = { vm.delete(vm.selectedEntries()) },
        onRestore = { vm.restore(vm.selectedEntries()) },
        onEmpty = vm::empty,
        onLock = { _ -> },
        onLockFolder = { _ -> },
        onLockSuggestions = {},
        onRequestAccess = {},
        thumb = { entry ->
            Box {
                LibraryThumb(entry)
                val days = TrashPolicy.daysRemaining(entry.expiresAtMillis, entry.deletedAtMillis, System.currentTimeMillis())
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                ) {
                    Text(
                        if (days <= 1) "1 day left" else "$days days left",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    )
}

@Composable
private fun FoldersRoute(onOpenFolder: (String) -> Unit) {
    val vm: FoldersViewModel = hiltViewModel()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Folders", subtitle = if (folders.isEmpty()) "Sources on this phone" else "${folders.size} sources")
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            folders.isEmpty() -> EmptyCopy(
                title = "No folders yet",
                body = "Camera, screenshots, WhatsApp, and anything else Android has indexed will show up here as their own folders.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(folders, key = { it.key }) { folder ->
                    FolderCard(folder, onOpen = { onOpenFolder(folder.key) }, onStopWatch = { vm.stopWatching(folder.key) })
                }
            }
        }
    }
}

@Composable
private fun FolderCard(folder: MediaFolder, onOpen: () -> Unit, onStopWatch: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))) {
                if (folder.coverUri != null) {
                    LibraryThumb(
                        GalleryEntry(
                            id = folder.key,
                            uri = folder.coverUri,
                            displayName = folder.title,
                            mimeType = "image/*",
                            isVideo = false,
                            dateMillis = folder.latestMillis,
                            sizeBytes = 0,
                            width = 0,
                            height = 0,
                            durationMs = 0,
                            relativePath = null,
                            bucketName = folder.title,
                            sourceId = folder.key,
                            sourceLabel = folder.title,
                            sourceKnown = folder.known,
                            folderKey = folder.key,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                    }
                }
                if (folder.autoLock) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "Auto-lock",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    CountLine(folder.imageCount, folder.videoCount)
                }
                if (folder.autoLock) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Folder options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Stop auto-lock") },
                            onClick = {
                                menu = false
                                onStopWatch()
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryBody(
    ui: BrowserUi,
    progress: LockProgress?,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    folderScreen: Boolean,
    trashScreen: Boolean,
    onFilter: (MediaFilter) -> Unit,
    onSort: (SortOrder) -> Unit,
    onColumns: (Int) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onOpen: (GalleryEntry) -> Unit,
    onToggle: (GalleryEntry) -> Unit,
    onStartSelect: (GalleryEntry) -> Unit,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: (android.content.Context) -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit = {},
    onEmpty: () -> Unit = {},
    onLock: (Boolean) -> Unit,
    onLockFolder: (Boolean) -> Unit,
    onLockSuggestions: () -> Unit,
    onRequestAccess: () -> Unit,
    thumb: @Composable (GalleryEntry) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var lockDialog by remember { mutableStateOf(false) }
    var lockWholeFolder by remember { mutableStateOf(false) }
    val selecting = ui.selected.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear) { Text("Cancel") }
                Text("${ui.selected.size} selected", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onSelectAll) { Text("All") }
                if (!trashScreen) {
                    IconButton(onClick = { onShare(context) }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                    IconButton(onClick = { lockWholeFolder = false; lockDialog = true }) {
                        Icon(Icons.Outlined.Lock, contentDescription = "Lock")
                    }
                } else {
                    TextButton(onClick = onRestore) { Text("Restore") }
                }
                IconButton(onClick = onTrash) {
                    Icon(Icons.Outlined.Delete, contentDescription = if (trashScreen) "Delete forever" else "Move to bin")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineMedium)
                    if (ui.subtitle.isNotBlank()) {
                        Text(ui.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!trashScreen) {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Library options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (folderScreen) {
                            DropdownMenuItem(text = { Text("Lock this folder") }, onClick = {
                                menu = false
                                lockWholeFolder = true
                                lockDialog = true
                            })
                        }
                        SortOrder.entries.forEach { order ->
                            DropdownMenuItem(text = { Text(if (order == ui.sort) "Sorted: ${order.label}" else order.label) }, onClick = {
                                menu = false
                                onSort(order)
                            })
                        }
                        DropdownMenuItem(text = { Text("Dark theme") }, onClick = { menu = false; onTheme(ThemeMode.Dark) })
                        DropdownMenuItem(text = { Text("Light theme") }, onClick = { menu = false; onTheme(ThemeMode.Light) })
                        DropdownMenuItem(text = { Text("Match system theme") }, onClick = { menu = false; onTheme(ThemeMode.System) })
                        DropdownMenuItem(
                            text = { Text(if (ui.settings.dynamicColor) "Dynamic color on" else "Dynamic color off") },
                            onClick = { menu = false; onDynamic(!ui.settings.dynamicColor) },
                        )
                    }
                } else {
                    TextButton(onClick = onEmpty) { Text("Empty bin") }
                }
            }
        }
        FilterRow(ui.filter, onFilter)
        if (ui.partial) {
            Banner(
                text = "Android is only sharing some photos and videos. The rest stay hidden from Media App until you allow more.",
                action = "Manage access",
                onAction = onRequestAccess,
            )
        }
        if (ui.suggestions > 0 && !trashScreen) {
            Banner(
                text = if (ui.suggestions == 1) {
                    "1 new file landed in a folder you asked to keep locking."
                } else {
                    "${ui.suggestions} new files landed in folders you asked to keep locking."
                },
                action = "Lock them",
                onAction = onLockSuggestions,
            )
        }
        ui.error?.let { Banner(text = it, action = null, onAction = {}) }
        progress?.let { lock ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Locking ${lock.name}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (lock.total == 0) 0f else lock.completed.toFloat() / lock.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !trashScreen && ui.access == AccessLevel.None && !folderScreen -> PermissionCopy(onRequestAccess)
                ui.items.isEmpty() -> EmptyCopy(
                    title = if (trashScreen) "The bin is empty" else "Nothing here yet",
                    body = if (trashScreen) {
                        "Photos and videos you delete stay here for about 30 days, then Android or Media App removes them."
                    } else {
                        "New photos and videos show up as soon as Android indexes them. Pinch the grid to change how many columns you see."
                    },
                )
                else -> MediaGrid(
                    items = ui.items,
                    columns = ui.columns,
                    sort = ui.sort,
                    selected = ui.selected,
                    selecting = selecting,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onStartSelect = onStartSelect,
                    onColumns = onColumns,
                    thumb = thumb,
                    modifier = Modifier.fillMaxSize(),
                    sharedElement = { entry ->
                        val base = this
                        with(sharedScope) {
                            base.sharedElement(
                                rememberSharedContentState(key = "media-${entry.id}"),
                                animatedVisibilityScope = animatedScope,
                            )
                        }
                    },
                )
            }
        }
    }
    if (lockDialog) {
        LockDialog(
            wholeFolder = lockWholeFolder,
            count = if (lockWholeFolder) ui.items.size else ui.selected.size,
            allowWatch = lockWholeFolder || ui.selected.let { ids ->
                ui.items.filter { it.id in ids }.map { it.folderKey }.distinct().size <= 1
            },
            onDismiss = { lockDialog = false },
            onConfirm = { watch ->
                lockDialog = false
                if (lockWholeFolder) onLockFolder(watch) else onLock(watch)
            },
        )
    }
}

@Composable
private fun FilterRow(current: MediaFilter, onFilter: (MediaFilter) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == current,
                onClick = { onFilter(filter) },
                label = {
                    Text(
                        when (filter) {
                            MediaFilter.All -> "All"
                            MediaFilter.Images -> "Images"
                            MediaFilter.Videos -> "Videos"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun LockDialog(
    wholeFolder: Boolean,
    count: Int,
    allowWatch: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var watch by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (wholeFolder) "Lock this folder?" else "Lock selected files?") },
        text = {
            Column {
                Text(
                    if (count == 1) {
                        "Media App encrypts this file into private storage, then asks Android to delete the original. Other galleries and file managers will no longer see it."
                    } else {
                        "Media App encrypts these $count files into private storage, then asks Android to delete the originals. Other galleries and file managers will no longer see them."
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Uninstalling Media App or clearing its data destroys locked files. The key stays in Android Keystore and cannot be exported yet.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (allowWatch) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = watch, onCheckedChange = { watch = it })
                        Text("Also offer to lock new files that arrive in this folder later")
                    }
                    Text(
                        "You'll confirm each time. Android 11 and later won't let an app delete new files silently.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(watch) }) { Text("Lock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCopy(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionCopy(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Photos and videos stay on your phone", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Media App reads them to build the library, plays video itself, and can lock the ones you choose. Nothing is uploaded.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRequest) { Text("Allow access") }
    }
}
