package com.kutedev.easemusicplayer.singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaylistDisplayMode {
    Grid,
    List,
}

@Singleton
class PlaylistLayoutRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val PREFS_NAME = "playlist_layout_settings"
        private const val KEY_DISPLAY_MODE = "display_mode"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(loadMode())

    val mode = _mode.asStateFlow()

    fun setMode(mode: PlaylistDisplayMode) {
        _mode.value = mode
        prefs.edit()
            .putString(KEY_DISPLAY_MODE, mode.name)
            .apply()
    }

    private fun loadMode(): PlaylistDisplayMode {
        val stored = prefs.getString(KEY_DISPLAY_MODE, null)
        return runCatching { PlaylistDisplayMode.valueOf(stored.orEmpty()) }
            .getOrDefault(PlaylistDisplayMode.Grid)
    }
}
