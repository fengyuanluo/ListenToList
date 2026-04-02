package com.kutedev.easemusicplayer.viewmodels

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.kutedev.easemusicplayer.singleton.PlaylistDisplayMode
import com.kutedev.easemusicplayer.singleton.PlaylistLayoutRepository
import com.kutedev.easemusicplayer.singleton.ThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject


@HiltViewModel
class ThemeVM @Inject constructor(
    private val repository: ThemeRepository,
    private val playlistLayoutRepository: PlaylistLayoutRepository,
) : ViewModel() {
    val settings = repository.settings
    val homePlaylistDisplayMode = playlistLayoutRepository.mode

    fun setPrimaryColor(color: Color) {
        repository.setPrimaryColor(color)
    }

    fun setBackgroundImage(uri: String?) {
        repository.setBackgroundImage(uri)
    }

    fun reset() {
        repository.reset()
    }

    fun setHomePlaylistDisplayMode(mode: PlaylistDisplayMode) {
        playlistLayoutRepository.setMode(mode)
    }
}
