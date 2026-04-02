package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.ViewModel
import com.kutedev.easemusicplayer.singleton.PlaylistDisplayMode
import com.kutedev.easemusicplayer.singleton.PlaylistLayoutRepository
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.ease_client_backend.PlaylistAbstract
import javax.inject.Inject

data class PlaylistsState(
    val playlists: List<PlaylistAbstract> = listOf()
)

enum class PlaylistsMode {
    Normal,
    Adjust
}

@HiltViewModel
class PlaylistsVM @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val playlistLayoutRepository: PlaylistLayoutRepository,
) : ViewModel() {
    private val _mode = MutableStateFlow(PlaylistsMode.Normal)
    val playlists = playlistRepository.playlists
    val displayMode = playlistLayoutRepository.mode

    val mode = _mode.asStateFlow()

    fun setMode(mode: PlaylistsMode) {
        _mode.value = mode
    }

    fun toggleMode() {
        _mode.value = when (_mode.value) {
            PlaylistsMode.Normal -> PlaylistsMode.Adjust
            PlaylistsMode.Adjust -> PlaylistsMode.Normal
        }
    }

    fun moveTo(fromIndex: Int, toIndex: Int) {
        playlistRepository.playlistMoveTo(fromIndex, toIndex)
    }

    fun commitMove() {
        playlistRepository.commitPendingPlaylistMove()
    }

    fun setDisplayMode(mode: PlaylistDisplayMode) {
        playlistLayoutRepository.setMode(mode)
    }
}
