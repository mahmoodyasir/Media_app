package com.mediaapp.player

import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mediaapp.data.vault.AndroidVaultCipher
import com.mediaapp.data.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object PlayerBridge {
    @Volatile var player: Player? = null
    @Volatile var allowPip: Boolean = false
    @Volatile var inPip: Boolean = false

    fun pause() {
        if (inPip) return
        player?.pause()
    }

    fun toggle() {
        val current = player ?: return
        if (current.isPlaying) current.pause() else current.play()
    }
}

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var vaultRepository: VaultRepository
    @Inject lateinit var vaultCipher: AndroidVaultCipher

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val vault = VaultDataSource.Factory(
            openFile = { id -> vaultRepository.mediaFile(id) },
            crypto = { vaultCipher.crypto() },
        )
        val routing = RoutingDataSource.Factory(DefaultDataSource.Factory(this), vault)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(routing))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exoPlayer
        PlayerBridge.player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).setId("media-app").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.pause()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (PlayerBridge.player === player) PlayerBridge.player = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        fun toggleIntent(context: android.content.Context): PendingIntent {
            val intent = Intent(context, PipActionReceiver::class.java).setAction(PipActionReceiver.ACTION_TOGGLE)
            return PendingIntent.getBroadcast(
                context,
                7,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
