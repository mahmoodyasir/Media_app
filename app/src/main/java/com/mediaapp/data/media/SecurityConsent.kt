package com.mediaapp.data.media

import android.app.PendingIntent
import android.content.IntentSender
import android.os.Build
import java.lang.SecurityException

fun SecurityException.userConsentSender(): IntentSender? {
    if (Build.VERSION.SDK_INT < 29) return null
    return try {
        val userAction = javaClass.methods.firstOrNull { it.name == "getUserAction" }?.invoke(this) ?: return null
        val pending = userAction.javaClass.methods.firstOrNull { it.name == "getActionIntent" }?.invoke(userAction) as? PendingIntent
        pending?.intentSender
    } catch (_: Throwable) {
        null
    }
}
