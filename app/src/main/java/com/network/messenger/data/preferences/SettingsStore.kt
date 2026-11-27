package com.network.messenger.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "network_messenger_settings")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class SettingsStore(private val context: Context) {
    val relayServerUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_RELAY_URL] ?: DEFAULT_RELAY_URL
    }

    val autoPlayVoice: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_PLAY_VOICE] ?: true
    }

    val activeNetworkId: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_NETWORK_ID]
    }

    val queueExpiryHours: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[KEY_QUEUE_EXPIRY_HOURS] ?: DEFAULT_QUEUE_EXPIRY_HOURS).coerceIn(1, 168)
    }

    val showLogsPanel: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SHOW_LOGS_PANEL] ?: DEFAULT_SHOW_LOGS_PANEL
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_THEME_MODE]
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    suspend fun setRelayServerUrl(url: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_RELAY_URL] = url.trim()
        }
    }

    suspend fun setAutoPlayVoice(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_PLAY_VOICE] = enabled
        }
    }

    suspend fun setActiveNetworkId(networkId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (networkId == null) prefs.remove(KEY_ACTIVE_NETWORK_ID)
            else prefs[KEY_ACTIVE_NETWORK_ID] = networkId
        }
    }

    suspend fun setQueueExpiryHours(hours: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_QUEUE_EXPIRY_HOURS] = hours.coerceIn(1, 168)
        }
    }

    suspend fun setShowLogsPanel(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SHOW_LOGS_PANEL] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    companion object {
        const val DEFAULT_RELAY_URL = "ws://10.0.2.2:8080"
        const val DEFAULT_QUEUE_EXPIRY_HOURS = 24
        const val DEFAULT_SHOW_LOGS_PANEL = true
        private val KEY_RELAY_URL = stringPreferencesKey("relay_server_url")
        private val KEY_AUTO_PLAY_VOICE = booleanPreferencesKey("auto_play_voice")
        private val KEY_ACTIVE_NETWORK_ID = stringPreferencesKey("active_network_id")
        private val KEY_QUEUE_EXPIRY_HOURS = intPreferencesKey("queue_expiry_hours")
        private val KEY_SHOW_LOGS_PANEL = booleanPreferencesKey("show_logs_panel")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
