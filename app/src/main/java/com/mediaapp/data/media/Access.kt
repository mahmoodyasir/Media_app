package com.mediaapp.data.media

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun Context.mediaAccess(): AccessLevel {
    val granted = MediaPermissions.readPermissions(android.os.Build.VERSION.SDK_INT)
        .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        .toSet()
    return MediaPermissions.accessLevel(android.os.Build.VERSION.SDK_INT, granted)
}
