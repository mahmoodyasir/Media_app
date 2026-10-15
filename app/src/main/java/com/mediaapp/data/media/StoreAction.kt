package com.mediaapp.data.media

import android.content.IntentSender

sealed interface StoreAction {
    data class Done(val message: String) : StoreAction
    data class NeedsConsent(val token: String, val intentSender: IntentSender) : StoreAction
    data class Failed(val message: String) : StoreAction
}
