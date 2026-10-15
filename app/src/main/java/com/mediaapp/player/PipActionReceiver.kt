package com.mediaapp.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) PlayerBridge.toggle()
    }

    companion object {
        const val ACTION_TOGGLE = "com.mediaapp.action.TOGGLE_PLAYBACK"
    }
}
