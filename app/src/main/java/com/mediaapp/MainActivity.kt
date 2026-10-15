package com.mediaapp

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediaapp.data.prefs.UserPreferencesRepository
import com.mediaapp.data.prefs.UserSettings
import com.mediaapp.player.PlayerBridge
import com.mediaapp.player.enterPlayerPip
import com.mediaapp.ui.MediaAppRoot
import com.mediaapp.ui.WindowSecurity
import com.mediaapp.ui.theme.MediaAppTheme
import com.mediaapp.ui.viewIntentUri
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

data class IncomingMedia(val uri: String, val mime: String?)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    private var incoming by mutableStateOf<IncomingMedia?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = incomingFrom(intent)
        setContent { AppContent() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = incomingFrom(intent) ?: incoming
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlayerBridge.allowPip && PlayerBridge.player?.isPlaying == true) {
            enterPlayerPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerBridge.inPip = isInPictureInPictureMode
    }

    @Composable
    private fun AppContent() {
        val settings by preferences.settings.collectAsStateWithLifecycle(UserSettings())
        val secure by WindowSecurity.secure.collectAsStateWithLifecycle()
        SideEffect {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        MediaAppTheme(mode = settings.theme, dynamicColor = settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
            MediaAppRoot(
                incoming = incoming,
                onIncomingConsumed = { incoming = null },
            )
        }
    }

    private fun incomingFrom(intent: Intent?): IncomingMedia? {
        val uri: Uri = viewIntentUri(intent) ?: return null
        return IncomingMedia(uri.toString(), intent?.type)
    }
}
