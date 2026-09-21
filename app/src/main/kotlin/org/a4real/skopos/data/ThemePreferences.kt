package org.a4real.skopos.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.a4real.skopos.ui.theme.ThemeMode

private val Context.settingsDataStore by preferencesDataStore(name = "skopos_settings")

class ThemePreferences(private val context: Context) {

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data
            .map { prefs -> ThemeMode.fromKey(prefs[KEY_THEME_MODE]) }
            .catch { e ->
                if (e is IOException) emit(ThemeMode.SYSTEM) else throw e
            }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = mode.key }
    }

    val dynamicColors: Flow<Boolean> =
        context.settingsDataStore.data
            .map { prefs -> prefs[KEY_DYNAMIC_COLORS] ?: true }
            .catch { e ->
                if (e is IOException) emit(true) else throw e
            }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_DYNAMIC_COLORS] = enabled }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
    }
}