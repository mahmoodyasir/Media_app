package com.mediaapp.ui

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediaapp.data.media.AccessLevel
import com.mediaapp.data.media.MediaRepository
import com.mediaapp.data.media.StoreAction
import com.mediaapp.data.media.TrashRepository
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.MediaFilter
import com.mediaapp.data.model.MediaFolder
import com.mediaapp.data.model.SortOrder
import com.mediaapp.data.model.WatchedFolder
import com.mediaapp.data.model.filtered
import com.mediaapp.data.model.sortedWith
import com.mediaapp.data.playback.PlaybackPositionStore
import com.mediaapp.data.prefs.UserPreferencesRepository
import com.mediaapp.data.prefs.UserSettings
import com.mediaapp.data.vault.AppLock
import com.mediaapp.data.vault.LockKind
import com.mediaapp.data.vault.VaultRepository
import com.mediaapp.data.vault.VaultSession
import com.mediaapp.data.vault.toEntry
import com.mediaapp.editor.image.ImageEditor
import com.mediaapp.editor.video.VideoEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserUi(
    val title: String = "Library",
    val subtitle: String = "",
    val items: List<GalleryEntry> = emptyList(),
    val filter: MediaFilter = MediaFilter.All,
    val sort: SortOrder = SortOrder.Newest,
    val columns: Int = 3,
    val settings: UserSettings = UserSettings(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val partial: Boolean = false,
    val access: AccessLevel = AccessLevel.None,
    val suggestions: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val media: MediaRepository,
    private val prefs: UserPreferencesRepository,
    private val vault: VaultRepository,
    private val trash: TrashRepository,
) : ViewModel() {
    private val folderKey: String? = savedStateHandle.get<String>("folderKey")?.let(RouteCodec::decode)
    private val filter = MutableStateFlow(MediaFilter.All)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()
    private var consentKind = ConsentKind.Trash

    val ui: StateFlow<BrowserUi> = combine(
        media.snapshot,
        prefs.settings,
        filter,
        selected,
        vault.suggestions,
    ) { snap, settings, currentFilter, selection, suggestions ->
        val scoped = if (folderKey == null) snap.items else snap.items.filter { it.folderKey == folderKey }
        val visible = scoped.filtered(currentFilter).sortedWith(settings.sort)
        val title = if (folderKey == null) "Library" else scoped.firstOrNull()?.sourceLabel ?: "Folder"
        BrowserUi(
            title = title,
            subtitle = countLabel(scoped),
            items = visible,
            filter = currentFilter,
            sort = settings.sort,
            columns = settings.columns,
            settings = settings,
            selected = selection,
            loading = !snap.loaded,
            partial = snap.access == AccessLevel.Partial,
            access = snap.access,
            suggestions = if (folderKey == null) suggestions.size else suggestions.count { it.folderKey == folderKey },
            error = snap.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUi(loading = true))

    val progress = vault.progress
    val folderKeyValue: String? get() = folderKey

    fun setFilter(value: MediaFilter) {
        filter.value = value
        selected.value = emptySet()
    }

    fun setSort(value: SortOrder) {
        viewModelScope.launch { prefs.setSort(value) }
    }

    fun setColumns(value: Int) {
        viewModelScope.launch { prefs.setColumns(value) }
    }

    fun setTheme(mode: com.mediaapp.data.model.ThemeMode) {
        viewModelScope.launch { prefs.setTheme(mode) }
    }

    fun setDynamic(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    fun toggle(entry: GalleryEntry) {
        selected.value = if (entry.id in selected.value) selected.value - entry.id else selected.value + entry.id
    }

    fun startSelect(entry: GalleryEntry) {
        selected.value = selected.value + entry.id
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun selectAll() {
        selected.value = ui.value.items.map { it.id }.toSet()
    }

    fun refresh() {
        media.refreshAsync()
    }

    fun selectedEntries(): List<GalleryEntry> {
        val ids = selected.value
        return ui.value.items.filter { it.id in ids }
    }

    fun trashSelected() = trash(selectedEntries())

    fun trash(entries: List<GalleryEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            consentKind = ConsentKind.Trash
            deliver(trash.trash(entries))
            selected.value = emptySet()
        }
    }

    fun lockSelected(watch: Boolean) = lock(selectedEntries(), watch)

    fun lock(entries: List<GalleryEntry>, watch: Boolean) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            consentKind = ConsentKind.Lock
            val folder = if (watch) {
                entries.map { it.folderKey }.distinct().singleOrNull()?.let { key ->
                    WatchedFolder(key, entries.first().sourceLabel)
                }
            } else {
                null
            }
            deliver(vault.lock(entries, folder))
            selected.value = emptySet()
            media.refresh()
        }
    }

    fun lockFolder(watch: Boolean) {
        val key = folderKey ?: return
        val entries = media.snapshot.value.items.filter { it.folderKey == key }
        lock(entries, watch)
    }

    fun lockSuggestions() {
        viewModelScope.launch {
            consentKind = ConsentKind.Lock
            val items = vault.suggestions.value.filter { folderKey == null || it.folderKey == folderKey }
            deliver(vault.lock(items, null))
            media.refresh()
        }
    }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        viewModelScope.launch {
            val result = if (consentKind == ConsentKind.Lock) vault.onConsent(pending.token, accepted)
            else trash.onConsent(pending.token, accepted)
            deliver(result)
            media.refresh()
        }
    }

    fun share(context: Context, entries: List<GalleryEntry>) {
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                entries.map { entry ->
                    if (entry.vault) {
                        val file = vault.prepareShareFile(entry)
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    } else {
                        Uri.parse(entry.uri)
                    }
                }
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = entries.first().mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = if (entries.all { it.isVideo }) "video/*" else if (entries.none { it.isVideo }) "image/*" else "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    private suspend fun deliver(action: StoreAction) {
        when (action) {
            is StoreAction.NeedsConsent -> _consent.value = action
            is StoreAction.Done -> _messages.emit(action.message)
            is StoreAction.Failed -> _messages.emit(action.message)
        }
    }

    private enum class ConsentKind { Lock, Trash }
}

@HiltViewModel
class FoldersViewModel @Inject constructor(
    media: MediaRepository,
    private val vault: VaultRepository,
) : ViewModel() {
    val folders: StateFlow<List<MediaFolder>> = combine(media.snapshot, vault.index) { snap, index ->
        com.mediaapp.data.model.FolderIndex.build(snap.items, index.watched.map { it.key }.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loading: StateFlow<Boolean> = media.snapshot
        .combine(vault.index) { snap, _ -> !snap.loaded }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun stopWatching(key: String) {
        viewModelScope.launch { vault.forgetWatch(key) }
    }
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trash: TrashRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(MediaFilter.All)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()

    val ui: StateFlow<BrowserUi> = combine(trash.items, prefs.settings, filter, selected) { items, settings, current, selection ->
        BrowserUi(
            title = "Recycle bin",
            subtitle = "Kept for 30 days",
            items = items.filtered(current).sortedWith(settings.sort),
            filter = current,
            sort = settings.sort,
            columns = settings.columns,
            settings = settings,
            selected = selection,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUi(title = "Recycle bin", loading = true))

    init {
        viewModelScope.launch { trash.refresh() }
    }

    fun setFilter(value: MediaFilter) {
        filter.value = value
    }

    fun setColumns(value: Int) {
        viewModelScope.launch { prefs.setColumns(value) }
    }

    fun toggle(entry: GalleryEntry) {
        selected.value = if (entry.id in selected.value) selected.value - entry.id else selected.value + entry.id
    }

    fun startSelect(entry: GalleryEntry) {
        selected.value = selected.value + entry.id
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun selectAll() {
        selected.value = ui.value.items.map { it.id }.toSet()
    }

    fun selectedEntries(): List<GalleryEntry> = ui.value.items.filter { it.id in selected.value }

    fun restore(entries: List<GalleryEntry>) = act { trash.restore(entries) }
    fun delete(entries: List<GalleryEntry>) = act { trash.deleteForever(entries) }
    fun empty() = act { trash.empty() }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        viewModelScope.launch {
            when (val result = trash.onConsent(pending.token, accepted)) {
                is StoreAction.Done -> _messages.emit(result.message)
                is StoreAction.Failed -> _messages.emit(result.message)
                is StoreAction.NeedsConsent -> _consent.value = result
            }
            selected.value = emptySet()
        }
    }

    private fun act(block: suspend () -> StoreAction) {
        viewModelScope.launch {
            when (val result = block()) {
                is StoreAction.NeedsConsent -> _consent.value = result
                is StoreAction.Done -> {
                    _messages.emit(result.message)
                    selected.value = emptySet()
                }
                is StoreAction.Failed -> _messages.emit(result.message)
            }
        }
    }
}

enum class VaultGate { Loading, Choose, EnterPin, ConfirmPin, EnterPattern, ConfirmPattern, Unlock, Open }

data class VaultUi(
    val gate: VaultGate = VaultGate.Loading,
    val kind: LockKind? = null,
    val biometric: Boolean = false,
    val items: List<GalleryEntry> = emptyList(),
    val filter: MediaFilter = MediaFilter.All,
    val sort: SortOrder = SortOrder.Newest,
    val columns: Int = 3,
    val selected: Set<String> = emptySet(),
    val draft: String = "",
    val error: String? = null,
    val cooldownMs: Long = 0L,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vault: VaultRepository,
    val session: VaultSession,
    private val appLock: AppLock,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(MediaFilter.All)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val gate = MutableStateFlow(VaultGate.Loading)
    private val draft = MutableStateFlow("")
    private val firstSecret = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val biometricOn = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()
    val progress = vault.progress

    private data class GateSlice(
        val gate: VaultGate,
        val unlocked: Boolean,
        val biometric: Boolean,
        val draft: String,
        val error: String?,
    )

    private val gateSlice = combine(gate, session.unlocked, biometricOn, draft, error) { currentGate, unlocked, biometric, currentDraft, currentError ->
        GateSlice(currentGate, unlocked, biometric, currentDraft, currentError)
    }

    val ui: StateFlow<VaultUi> = combine(
        gateSlice,
        vault.index,
        prefs.settings,
        filter,
        selected,
    ) { slice, index, settings, currentFilter, selection ->
        val entries = index.records.filter { it.state == com.mediaapp.data.vault.VaultItemState.COMMITTED }
            .map { it.toEntry() }
            .filtered(currentFilter)
            .sortedWith(settings.sort)
        val shown = when {
            slice.gate == VaultGate.Loading -> VaultGate.Loading
            !appLock.hasCredential() -> slice.gate
            slice.unlocked -> VaultGate.Open
            else -> VaultGate.Unlock
        }
        VaultUi(
            gate = shown,
            kind = appLock.kind(),
            biometric = slice.biometric,
            items = entries,
            filter = currentFilter,
            sort = settings.sort,
            columns = settings.columns,
            selected = selection,
            draft = slice.draft,
            error = slice.error,
            cooldownMs = session.cooldownRemaining(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUi())

    init {
        viewModelScope.launch {
            vault.ensureLoaded()
            biometricOn.value = appLock.biometricEnabled()
            gate.value = if (appLock.hasCredential()) VaultGate.Unlock else VaultGate.Choose
        }
    }

    fun choosePin() {
        firstSecret.value = null
        draft.value = ""
        gate.value = VaultGate.EnterPin
    }

    fun choosePattern() {
        firstSecret.value = null
        draft.value = ""
        gate.value = VaultGate.EnterPattern
    }

    fun appendDigit(digit: String) {
        if (draft.value.length >= 12) return
        draft.value += digit
        error.value = null
    }

    fun backspace() {
        draft.value = draft.value.dropLast(1)
    }

    fun submitPin() {
        val pin = draft.value
        if (pin.length < 4) {
            error.value = "Use at least 4 digits"
            return
        }
        when (gate.value) {
            VaultGate.EnterPin -> {
                firstSecret.value = pin
                draft.value = ""
                gate.value = VaultGate.ConfirmPin
            }
            VaultGate.ConfirmPin -> {
                if (pin != firstSecret.value) {
                    error.value = "Those PINs don't match"
                    draft.value = ""
                    return
                }
                appLock.setPin(pin)
                session.unlock()
                gate.value = VaultGate.Open
            }
            VaultGate.Unlock -> verify(pin, pattern = false)
            else -> Unit
        }
    }

    fun submitPattern(pattern: List<Int>) {
        if (pattern.size < 4) {
            error.value = "Connect at least 4 dots"
            return
        }
        val secret = pattern.joinToString(",")
        when (gate.value) {
            VaultGate.EnterPattern -> {
                firstSecret.value = secret
                gate.value = VaultGate.ConfirmPattern
                error.value = null
            }
            VaultGate.ConfirmPattern -> {
                if (secret != firstSecret.value) {
                    error.value = "Those patterns don't match"
                    gate.value = VaultGate.EnterPattern
                    return
                }
                appLock.setPattern(pattern)
                session.unlock()
                gate.value = VaultGate.Open
            }
            VaultGate.Unlock -> verify(secret, pattern = true)
            else -> Unit
        }
    }

    fun unlockBiometric() {
        session.unlock()
    }

    fun lockNow() {
        session.lock()
        selected.value = emptySet()
    }

    fun setBiometric(enabled: Boolean) {
        appLock.setBiometric(enabled)
        biometricOn.value = appLock.biometricEnabled()
    }

    fun setFilter(value: MediaFilter) {
        filter.value = value
    }

    fun setColumns(value: Int) {
        viewModelScope.launch { prefs.setColumns(value) }
    }

    fun toggle(entry: GalleryEntry) {
        selected.value = if (entry.id in selected.value) selected.value - entry.id else selected.value + entry.id
    }

    fun startSelect(entry: GalleryEntry) {
        selected.value = selected.value + entry.id
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun selectAll() {
        selected.value = ui.value.items.map { it.id }.toSet()
    }

    fun selectedEntries(): List<GalleryEntry> = ui.value.items.filter { it.id in selected.value }

    fun restore(entries: List<GalleryEntry>) {
        viewModelScope.launch {
            when (val result = vault.restore(entries.map { it.id })) {
                is StoreAction.Done -> _messages.emit(result.message)
                is StoreAction.Failed -> _messages.emit(result.message)
                is StoreAction.NeedsConsent -> _consent.value = result
            }
            selected.value = emptySet()
        }
    }

    fun destroy(entries: List<GalleryEntry>) {
        viewModelScope.launch {
            vault.destroy(entries.map { it.id })
            selected.value = emptySet()
            _messages.emit("Deleted from the vault")
        }
    }

    fun finishPending() {
        viewModelScope.launch {
            when (val result = vault.resumePending()) {
                is StoreAction.NeedsConsent -> _consent.value = result
                is StoreAction.Done -> _messages.emit(result.message)
                is StoreAction.Failed -> _messages.emit(result.message)
            }
        }
    }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        viewModelScope.launch {
            when (val result = vault.onConsent(pending.token, accepted)) {
                is StoreAction.Done -> _messages.emit(result.message)
                is StoreAction.Failed -> _messages.emit(result.message)
                is StoreAction.NeedsConsent -> _consent.value = result
            }
        }
    }

    fun thumb(entry: GalleryEntry) = vault.loadThumb(entry.id)

    fun decode(entry: GalleryEntry, maxSide: Int) = vault.decodeImage(entry.id, maxSide)

    private fun verify(secret: String, pattern: Boolean) {
        if (session.cooldownRemaining() > 0L) {
            error.value = "Wait a moment, then try again"
            return
        }
        val ok = if (pattern) appLock.verifyPattern(secret.split(",").mapNotNull { it.toIntOrNull() })
        else appLock.verifyPin(secret)
        if (ok) {
            draft.value = ""
            error.value = null
            session.unlock()
        } else {
            session.registerFailure()
            draft.value = ""
            error.value = "Wrong passcode"
        }
    }
}

data class ViewerUi(
    val items: List<GalleryEntry> = emptyList(),
    val startIndex: Int = 0,
    val ready: Boolean = false,
    val vault: Boolean = false,
)

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val media: MediaRepository,
    private val trash: TrashRepository,
    private val vault: VaultRepository,
    private val positions: PlaybackPositionStore,
    val requests: ViewerRequestStore,
    val videoEditor: VideoEditor,
    val imageEditor: ImageEditor,
) : ViewModel() {
    private val _items = MutableStateFlow<List<GalleryEntry>>(emptyList())
    val items: StateFlow<List<GalleryEntry>> = _items
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val _consent = MutableStateFlow<StoreAction.NeedsConsent?>(null)
    val consent = _consent.asStateFlow()

    val startIndex: Int
        get() {
            val id = requests.request.value?.startId ?: return 0
            return _items.value.indexOfFirst { it.id == id }.coerceAtLeast(0)
        }

    init {
        viewModelScope.launch {
            val request = requests.request.value
            _items.value = resolve(request)
        }
    }

    fun position(id: String, onLoaded: (Long) -> Unit) {
        viewModelScope.launch { onLoaded(positions.get(id)) }
    }

    fun savePosition(id: String, position: Long, duration: Long) {
        viewModelScope.launch { positions.set(id, position, duration) }
    }

    fun trash(entry: GalleryEntry) {
        viewModelScope.launch {
            when (val result = trash.trash(listOf(entry))) {
                is StoreAction.NeedsConsent -> _consent.value = result
                is StoreAction.Done -> {
                    _messages.emit(result.message)
                    drop(entry.id)
                    media.refresh()
                }
                is StoreAction.Failed -> _messages.emit(result.message)
            }
        }
    }

    fun onConsent(accepted: Boolean) {
        val pending = _consent.value ?: return
        _consent.value = null
        viewModelScope.launch {
            when (val result = trash.onConsent(pending.token, accepted)) {
                is StoreAction.Done -> _messages.emit(result.message)
                is StoreAction.Failed -> _messages.emit(result.message)
                is StoreAction.NeedsConsent -> _consent.value = result
            }
            media.refresh()
        }
    }

    fun restoreVault(entry: GalleryEntry) {
        viewModelScope.launch {
            when (val result = vault.restore(listOf(entry.id))) {
                is StoreAction.Done -> {
                    _messages.emit(result.message)
                    drop(entry.id)
                }
                is StoreAction.Failed -> _messages.emit(result.message)
                is StoreAction.NeedsConsent -> Unit
            }
        }
    }

    fun wallpaper(context: Context, entry: GalleryEntry) {
        if (entry.isVideo) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (entry.vault) vault.decodeImage(entry.id, 2400)
                else decodeContentBitmap(context, Uri.parse(entry.uri), 2400)
            }
            if (bitmap == null) {
                _messages.emit("Couldn't read that photo")
                return@launch
            }
            withContext(Dispatchers.IO) {
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
            _messages.emit("Wallpaper updated")
        }
    }

    suspend fun editMessage(entry: GalleryEntry, videoOp: com.mediaapp.editor.VideoEditOperation?, imageOp: com.mediaapp.editor.ImageEditOperation?): String {
        return if (entry.isVideo) {
            videoEditor.apply(
                com.mediaapp.editor.VideoEditRequest(entry.uri, entry.mimeType, videoOp ?: com.mediaapp.editor.VideoEditOperation.Trim),
            ).message
        } else {
            imageEditor.apply(
                com.mediaapp.editor.ImageEditRequest(entry.uri, entry.mimeType, imageOp ?: com.mediaapp.editor.ImageEditOperation.Crop),
            ).message
        }
    }

    fun decode(entry: GalleryEntry) = vault.decodeImage(entry.id, 2400)

    fun share(context: Context, entry: GalleryEntry) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                if (entry.vault) {
                    val file = vault.prepareShareFile(entry)
                    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                } else {
                    Uri.parse(entry.uri)
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = entry.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    private suspend fun resolve(request: ViewerRequest?): List<GalleryEntry> {
        if (request == null) return emptyList()
        return when (val origin = request.origin) {
            ViewerOrigin.Library -> media.snapshot.value.items.filtered(request.filter).sortedWith(request.sort)
            is ViewerOrigin.Folder -> media.snapshot.value.items
                .filter { it.folderKey == origin.key }
                .filtered(request.filter)
                .sortedWith(request.sort)
            ViewerOrigin.Vault -> vault.committedEntries().filtered(request.filter).sortedWith(request.sort)
            ViewerOrigin.Trash -> trash.items.value.filtered(request.filter).sortedWith(request.sort)
            is ViewerOrigin.External -> listOf(
                GalleryEntry(
                    id = "external",
                    uri = origin.uri,
                    displayName = origin.uri.substringAfterLast('/').substringBefore('?').ifBlank { "Opened file" },
                    mimeType = origin.mime ?: if (origin.uri.contains("video")) "video/*" else "image/*",
                    isVideo = (origin.mime ?: "").startsWith("video") || origin.uri.contains("video"),
                    dateMillis = System.currentTimeMillis(),
                    sizeBytes = -1,
                    width = 0,
                    height = 0,
                    durationMs = 0,
                    relativePath = null,
                    bucketName = null,
                    sourceId = "external",
                    sourceLabel = "Opened from another app",
                    sourceKnown = false,
                    folderKey = "external",
                ),
            )
        }
    }

    private fun drop(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }
}

@javax.inject.Singleton
class ViewerRequestStore @Inject constructor() {
    val request = MutableStateFlow<ViewerRequest?>(null)
    fun open(value: ViewerRequest) {
        request.value = value
    }
}

sealed interface ViewerOrigin {
    data object Library : ViewerOrigin
    data class Folder(val key: String) : ViewerOrigin
    data object Vault : ViewerOrigin
    data object Trash : ViewerOrigin
    data class External(val uri: String, val mime: String?) : ViewerOrigin
}

data class ViewerRequest(
    val origin: ViewerOrigin,
    val startId: String,
    val filter: MediaFilter,
    val sort: SortOrder,
)

private fun countLabel(items: List<GalleryEntry>): String {
    val photos = items.count { !it.isVideo }
    val videos = items.count { it.isVideo }
    return listOfNotNull(
        if (photos > 0) "$photos photos" else null,
        if (videos > 0) "$videos videos" else null,
    ).ifEmpty { listOf("Nothing here yet") }.joinToString(" · ")
}
