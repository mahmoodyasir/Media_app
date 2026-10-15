package com.mediaapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.mediaapp.data.media.MediaRepository
import com.mediaapp.data.media.TrashRepository
import com.mediaapp.data.vault.VaultRepository
import com.mediaapp.data.vault.VaultSession
import com.mediaapp.player.PlayerBridge
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MediaApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var media: MediaRepository
    @Inject lateinit var trash: TrashRepository
    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var session: VaultSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        media.start()
        scope.launch {
            vault.ensureLoaded()
            media.snapshot.collect { snapshot -> vault.updateSuggestions(snapshot.items) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                media.refreshAsync()
                scope.launch { trash.refresh() }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!session.suppressAutoLock) {
                    session.lock()
                    vault.clearShareCache()
                }
                PlayerBridge.pause()
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
}
