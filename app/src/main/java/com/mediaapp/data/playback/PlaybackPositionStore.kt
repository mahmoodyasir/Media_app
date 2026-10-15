package com.mediaapp.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore(name = "playback_positions")

@Singleton
class PlaybackPositionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun get(id: String): Long {
        val prefs = context.playbackDataStore.data.first()
        return prefs[longPreferencesKey(key(id))] ?: 0L
    }

    suspend fun set(id: String, positionMs: Long, durationMs: Long) {
        val stored = when {
            positionMs < 5_000L -> 0L
            durationMs > 0L && durationMs - positionMs < 5_000L -> 0L
            else -> positionMs
        }
        context.playbackDataStore.edit { prefs ->
            val pref = longPreferencesKey(key(id))
            if (stored <= 0L) prefs.remove(pref) else prefs[pref] = stored
        }
    }

    private fun key(id: String) = "p_" + id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
}
