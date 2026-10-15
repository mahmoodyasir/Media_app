package com.mediaapp.data.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: MediaStoreReader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(LibrarySnapshot())
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()
    private var started = false
    private var observeJob: Job? = null

    fun start() {
        if (started) return
        started = true
        observeJob = scope.launch {
            refresh()
            callbackFlow {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
                val resolver = context.contentResolver
                resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
                resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
                awaitClose { resolver.unregisterContentObserver(observer) }
            }.debounce(400).collect { refresh() }
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        mutex.withLock {
            try {
                val items = reader.load(trashedOnly = false)
                _snapshot.value = LibrarySnapshot(
                    items = items,
                    access = context.mediaAccess(),
                    loaded = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Library refresh failed", error)
                _snapshot.value = _snapshot.value.copy(
                    access = context.mediaAccess(),
                    loaded = true,
                    error = error.message ?: "Couldn't read the library",
                )
            }
        }
    }

    private companion object {
        const val TAG = "MediaRepository"
    }
}
