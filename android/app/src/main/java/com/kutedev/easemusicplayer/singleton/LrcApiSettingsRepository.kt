package com.kutedev.easemusicplayer.singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LrcApiSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val authKey: String = "",
)

fun normalizeLrcApiBaseUrl(value: String): String {
    return value.trim().trimEnd('/')
}

fun LrcApiSettings.isReadyForFetch(): Boolean {
    return enabled && baseUrl.isNotBlank()
}

@Singleton
class LrcApiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val PREFS_NAME = "lrcapi_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_KEY = "auth_key"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())

    val settings = _settings.asStateFlow()

    fun save(settings: LrcApiSettings) {
        val normalized = settings.copy(
            baseUrl = normalizeLrcApiBaseUrl(settings.baseUrl),
            authKey = settings.authKey.trim(),
        )
        _settings.value = normalized
        persist(normalized)
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    fun setBaseUrl(baseUrl: String) {
        update { it.copy(baseUrl = normalizeLrcApiBaseUrl(baseUrl)) }
    }

    fun setAuthKey(authKey: String) {
        update { it.copy(authKey = authKey.trim()) }
    }

    fun reset() {
        save(LrcApiSettings())
    }

    private fun update(block: (LrcApiSettings) -> LrcApiSettings) {
        val next = block(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun loadSettings(): LrcApiSettings {
        return LrcApiSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            baseUrl = normalizeLrcApiBaseUrl(prefs.getString(KEY_BASE_URL, "").orEmpty()),
            authKey = prefs.getString(KEY_AUTH_KEY, "").orEmpty().trim(),
        )
    }

    private fun persist(settings: LrcApiSettings) {
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)

        if (settings.baseUrl.isBlank()) {
            editor.remove(KEY_BASE_URL)
        } else {
            editor.putString(KEY_BASE_URL, settings.baseUrl)
        }

        if (settings.authKey.isBlank()) {
            editor.remove(KEY_AUTH_KEY)
        } else {
            editor.putString(KEY_AUTH_KEY, settings.authKey)
        }

        editor.apply()
    }
}
