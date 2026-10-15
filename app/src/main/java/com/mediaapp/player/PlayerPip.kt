package com.mediaapp.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.mediaapp.R

fun Activity.enterPlayerPip(): Boolean {
    if (Build.VERSION.SDK_INT < 26) return false
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    if (!PlayerBridge.allowPip) return false
    return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
}

private fun Activity.pipParams(): PictureInPictureParams {
    val size = PlayerBridge.player?.videoSize
    val width = (size?.width ?: 16).coerceAtLeast(1)
    val height = (size?.height ?: 9).coerceAtLeast(1)
    val ratio = (width.toFloat() / height.toFloat()).coerceIn(0.42f, 2.39f)
    val rational = Rational((ratio * 1000).toInt().coerceAtLeast(1), 1000)
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(rational)
        .setActions(
            listOf(
                RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_stat_media),
                    "Play or pause",
                    "Play or pause",
                    PlaybackService.toggleIntent(this),
                ),
            ),
        )
    if (Build.VERSION.SDK_INT >= 31) {
        builder.setAutoEnterEnabled(PlayerBridge.allowPip && PlayerBridge.player?.isPlaying == true)
    }
    return builder.build()
}
