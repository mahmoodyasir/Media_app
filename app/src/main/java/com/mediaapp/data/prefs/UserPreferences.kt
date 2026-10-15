package com.mediaapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediaapp.data.model.SortOrder
import com.mediaapp.data.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserSettings(
    val theme: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = true,
    val sort: SortOrder = SortOrder.Newest,
    val columns: Int = 3,
    val playbackSpeed: Float = 1f,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            theme = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: ThemeMode.Dark.name) }
                .getOrDefault(ThemeMode.Dark),
            dynamicColor = prefs[Keys.DYNAMIC] ?: true,
            sort = runCatching { SortOrder.valueOf(prefs[Keys.SORT] ?: SortOrder.Newest.name) }
                .getOrDefault(SortOrder.Newest),
            columns = (prefs[Keys.COLUMNS] ?: 3).coerceIn(2, 6),
            playbackSpeed = prefs[Keys.SPEED] ?: 1f,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setSort(order: SortOrder) = edit { it[Keys.SORT] = order.name }
    suspend fun setColumns(columns: Int) = edit { it[Keys.COLUMNS] = columns.coerceIn(2, 6) }
    suspend fun setSpeed(speed: Float) = edit { it[Keys.SPEED] = speed }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SORT = stringPreferencesKey("sort")
        val COLUMNS = intPreferencesKey("columns")
        val SPEED = floatPreferencesKey("playback_speed")
    }
}
