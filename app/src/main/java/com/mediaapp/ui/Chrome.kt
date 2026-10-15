package com.mediaapp.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.mediaapp.data.media.MediaPermissions
import com.mediaapp.data.media.StoreAction
import kotlinx.coroutines.flow.Flow

@Composable
fun CollectMessages(
    messages: Flow<String>,
    snackbar: SnackbarHostState,
    quiet: Set<String> = setOf("Nothing waiting"),
) {
    LaunchedEffect(messages) {
        messages.collect { message ->
            if (message !in quiet) snackbar.showSnackbar(message)
        }
    }
}

@Composable
fun ConsentEffect(consent: StoreAction.NeedsConsent?, onResult: (Boolean) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        onResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent?.token) {
        val pending = consent ?: return@LaunchedEffect
        val request = androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build()
        runCatching { launcher.launch(request) }.onFailure { onResult(false) }
    }
}

@Composable
fun rememberMediaPermissionRequest(onFinished: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onFinished()
    }
    return {
        val sdk = android.os.Build.VERSION.SDK_INT
        val permissions = MediaPermissions.readPermissions(sdk).toMutableList()
        if (sdk <= 28) permissions += MediaPermissions.writePermissions(sdk).toList()
        launcher.launch(permissions.distinct().toTypedArray())
    }
}
