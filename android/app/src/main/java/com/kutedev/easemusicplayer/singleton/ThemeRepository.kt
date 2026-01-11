package com.kutedev.easemusicplayer.singleton

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kutedev.easemusicplayer.ui.theme.ThemeSettings


@Singleton
class ThemeRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val PREFS_NAME = "theme_settings"
        private const val KEY_PRIMARY_COLOR = "primary_color"
        private const val KEY_BACKGROUND_URI = "background_uri"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())

    val settings = _settings.asStateFlow()

    fun setPrimaryColor(color: Color) {
        update { it.copy(primaryColor = color) }
    }

    fun setBackgroundImage(uri: String?) {
        update { it.copy(backgroundImageUri = uri) }
    }

    fun reset() {
        update { ThemeSettings() }
    }

    private fun update(block: (ThemeSettings) -> ThemeSettings) {
        val next = block(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun loadSettings(): ThemeSettings {
        val default = ThemeSettings()
        val color = Color(prefs.getInt(KEY_PRIMARY_COLOR, default.primaryColor.toArgb()))
        val background = prefs.getString(KEY_BACKGROUND_URI, default.backgroundImageUri)
        return ThemeSettings(
            primaryColor = color,
            backgroundImageUri = background,
        )
    }

    private fun persist(settings: ThemeSettings) {
        prefs.edit()
            .putInt(KEY_PRIMARY_COLOR, settings.primaryColor.toArgb())
            .apply()

        val editor = prefs.edit()
        if (settings.backgroundImageUri.isNullOrBlank()) {
            editor.remove(KEY_BACKGROUND_URI)
        } else {
            editor.putString(KEY_BACKGROUND_URI, settings.backgroundImageUri)
        }
        editor.apply()
    }
}
