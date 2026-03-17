package com.kutedev.easemusicplayer.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.core.syncMetadataUtil
import com.kutedev.easemusicplayer.singleton.ImportRepository
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.utils.ReorderCommit
import com.kutedev.easemusicplayer.utils.buildReorderMutation
import com.kutedev.easemusicplayer.utils.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.ArgAddMusicsToPlaylist
import uniffi.ease_client_backend.ArgReorderMusic
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.PlaylistAbstract
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_backend.PlaylistMeta
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ToAddMusicEntry
import uniffi.ease_client_backend.ctAddMusicsToPlaylist
import uniffi.ease_client_backend.ctGetPlaylist
import uniffi.ease_client_backend.ctsReorderMusicInPlaylist
import java.time.Duration
import javax.inject.Inject

private fun defaultPlaylistAbstract(): PlaylistAbstract {
    return PlaylistAbstract(
        meta = PlaylistMeta(
            id = PlaylistId(0),
            title = "",
            cover = null,
            showCover = null,
            createdTime = Duration.ofMillis(0L),
            order = listOf(0u)
        ),
        musicCount = 0uL,
        duration = null
    )
}

@HiltViewModel
@OptIn(FlowPreview::class)
class PlaylistVM @Inject constructor(
    private val bridge: Bridge,
    private val playlistRepository: PlaylistRepository,
    private val storageRepository: StorageRepository,
    private val importRepository: ImportRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _id: PlaylistId = PlaylistId(savedStateHandle["id"]!!)
    private val _removeModalOpen = MutableStateFlow(false)
    private val _playlistAbstr = MutableStateFlow(defaultPlaylistAbstract())
    private val _playlistMusics = MutableStateFlow(persistentListOf<MusicAbstract>())
    private var pendingMusicReorder: ReorderCommit<MusicId>? = null
    val removeModalOpen = _removeModalOpen.asStateFlow()
    val playlistAbstr = _playlistAbstr.asStateFlow()
    val playlistMusics = _playlistMusics.asStateFlow()

    init {
        viewModelScope.launch {
            reload()
            playlistRepository.playlists.collect {
                    _ -> reload()
            }
        }
        viewModelScope.launch {
            playlistRepository.syncedTotalDuration.debounce(500).collect {
                reload()
            }
        }
        viewModelScope.launch {
            storageRepository.onRemoveStorageEvent.collect {
                reload()
            }
        }
    }

    fun remove() {
        playlistRepository.removePlaylist(_id)
    }

    fun removeMusic(id: MusicId) {
        viewModelScope.launch {
            playlistRepository.removeMusic(_id, id)
        }
    }

    fun prepareImportMusics(context: Context) {
        importRepository.prepare(listOf(StorageEntryType.MUSIC)) {
            entries ->
                viewModelScope.launch {
                    val added = bridge.run { backend ->
                        ctAddMusicsToPlaylist(
                            backend, ArgAddMusicsToPlaylist(
                            id = _id,
                            entries = entries.map { entry ->
                                ToAddMusicEntry(
                                    entry = entry,
                                    name = entry.name
                                )
                            }
                    ))} ?: emptyList()
                    playlistRepository.requestTotalDuration(added)
                    playlistRepository.reload()
                }
        }
    }

    fun musicMoveTo(fromIndex: Int, toIndex: Int) {
        val mutation = buildReorderMutation(
            items = _playlistMusics.value,
            fromIndex = fromIndex,
            toIndex = toIndex,
            idOf = { it.meta.id },
        ) ?: return

        _playlistMusics.value = mutation.reorderedItems.toPersistentList()
        pendingMusicReorder = mutation.commit
    }

    fun commitMusicMove() {
        val pending = pendingMusicReorder ?: return
        pendingMusicReorder = null

        viewModelScope.launch {
            bridge.runSync {
                ctsReorderMusicInPlaylist(
                    it,
                    ArgReorderMusic(
                        playlistId = _playlistAbstr.value.meta.id,
                        id = pending.movedId,
                        a = pending.previousId,
                        b = pending.nextId,
                    )
                )
            }
            playlistRepository.scheduleReload()
            reload()
        }
    }

    fun openRemoveModal() {
        _removeModalOpen.value = true
    }

    fun closeRemoveModal() {
        _removeModalOpen.value = false
    }

    private suspend fun reload() {
        val playlist = bridge.run { backend -> ctGetPlaylist(backend, _id) }
        if (playlist != null) {
            _playlistAbstr.value = playlist.abstr
            _playlistMusics.value = playlist.musics.toPersistentList()
            playerControllerRepository.refreshPlaylistIfMatch(playlist)
        } else {
            _playlistAbstr.value = defaultPlaylistAbstract()
            _playlistMusics.value = persistentListOf()
        }
    }
}

fun PlaylistAbstract.durationStr(): String {
    return formatDuration(duration)
}

fun MusicAbstract.durationStr(): String {
    return formatDuration(meta.duration)
}
