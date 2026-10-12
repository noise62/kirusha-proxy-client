package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.PingType
import com.example.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("kirusha_vpn_settings", Context.MODE_PRIVATE)

    private val _pingType = MutableStateFlow(
        PingType.values().getOrElse(prefs.getInt(KEY_PING_TYPE, 0)) { PingType.TCP_HANDSHAKE }
    )
    val pingType: StateFlow<PingType> = _pingType.asStateFlow()

    private val _pingTimeoutMs = MutableStateFlow(prefs.getLong(KEY_PING_TIMEOUT, 3000L))
    val pingTimeoutMs: StateFlow<Long> = _pingTimeoutMs.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.values().getOrElse(prefs.getInt(KEY_THEME_MODE, 0)) { ThemeMode.SYSTEM }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _customSeedColor = MutableStateFlow(prefs.getLong(KEY_CUSTOM_SEED_COLOR, 0xFF68B456L))
    val customSeedColor: StateFlow<Long> = _customSeedColor.asStateFlow()

    private val _selectedServerId = MutableStateFlow(prefs.getLong(KEY_SELECTED_SERVER_ID, 1L))
    val selectedServerId: StateFlow<Long> = _selectedServerId.asStateFlow()

    fun setPingType(type: PingType) {
        prefs.edit().putInt(KEY_PING_TYPE, type.ordinal).apply()
        _pingType.value = type
    }

    fun setPingTimeoutMs(timeout: Long) {
        prefs.edit().putLong(KEY_PING_TIMEOUT, timeout).apply()
        _pingTimeoutMs.value = timeout
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode.ordinal).apply()
        _themeMode.value = mode
    }

    fun setCustomSeedColor(colorArgb: Long) {
        prefs.edit().putLong(KEY_CUSTOM_SEED_COLOR, colorArgb).apply()
        _customSeedColor.value = colorArgb
    }

    fun setSelectedServerId(id: Long) {
        prefs.edit().putLong(KEY_SELECTED_SERVER_ID, id).apply()
        _selectedServerId.value = id
    }

    companion object {
        private const val KEY_PING_TYPE = "pref_ping_type"
        private const val KEY_PING_TIMEOUT = "pref_ping_timeout"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_CUSTOM_SEED_COLOR = "pref_custom_seed_color"
        private const val KEY_SELECTED_SERVER_ID = "pref_selected_server_id"
    }
}
