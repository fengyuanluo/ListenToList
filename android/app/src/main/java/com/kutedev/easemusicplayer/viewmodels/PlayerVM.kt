package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import com.kutedev.easemusicplayer.singleton.LrcApiRepository
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import com.kutedev.easemusicplayer.core.probeMusicMetadataDirectly
import com.kutedev.easemusicplayer.utils.formatDuration
import com.kutedev.easemusicplayer.utils.normalizeMusicDisplayArtist
import com.kutedev.easemusicplayer.utils.resolveMusicDisplayTitle
import com.kutedev.easemusicplayer.utils.resolveTotalDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId
import java.time.Duration
import javax.inject.Inject
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.time.toJavaDuration

@StringRes
internal fun playModeToastLabelResId(playMode: PlayMode): Int {
    return when (playMode) {
        PlayMode.SINGLE -> R.string.music_play_mode_single
        PlayMode.SINGLE_LOOP -> R.string.music_play_mode_single_loop
        PlayMode.LIST -> R.string.music_play_mode_list
        PlayMode.LIST_LOOP -> R.string.music_play_mode_list_loop
    }
}

data class CurrentMusicDisplayInfo(
    val title: String = "",
    val artist: String = "",
)

@HiltViewModel
class PlayerVM @Inject constructor(
    private val bridge: Bridge,
    private val playerRepository: PlayerRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    private val downloadRepository: DownloadRepository,
    private val lrcApiRepository: LrcApiRepository,
    private val toastRepository: ToastRepository,
) : ViewModel() {
    private val _currentDuration = MutableStateFlow(Duration.ZERO)
    private val _bufferDuration = MutableStateFlow(Duration.ZERO)
    private val _totalDuration = MutableStateFlow(null as Duration?)
    private val _currentMusicDisplayInfo = MutableStateFlow(CurrentMusicDisplayInfo())
    val music = playerRepository.music
    val playlist = playerRepository.playlist
    val playbackQueue = playerRepository.playbackQueue
    val currentQueueEntryId = playerRepository.currentQueueEntryId
    val currentQueueEntry = playerRepository.currentQueueEntry
    val previousMusic = playerRepository.previousMusic
    val nextMusic = playerRepository.nextMusic
    val playing = playerRepository.playing
    val currentDuration = _currentDuration.asStateFlow()
    val bufferDuration = _bufferDuration.asStateFlow()
    val totalDuration = _totalDuration.asStateFlow()
    val playMode = playerRepository.playMode
    val loading = playerRepository.loading
    val currentMusicDisplayInfo = _currentMusicDisplayInfo.asStateFlow()

    val displayTotalDuration = combine(totalDuration, music) {
        runtimeDuration, currentMusic ->
            resolveTotalDuration(runtimeDuration, currentMusic?.meta?.duration)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            while (true) {
                syncPosition()
                delay(1000)
            }
        }
        viewModelScope.launch {
            playerRepository.durationChanged.collect {
                syncPosition()
            }
        }
        viewModelScope.launch {
            music.collectLatest { currentMusic ->
                if (currentMusic == null) {
                    _currentMusicDisplayInfo.value = CurrentMusicDisplayInfo()
                    return@collectLatest
                }

                _currentMusicDisplayInfo.value = CurrentMusicDisplayInfo(
                    title = resolveMusicDisplayTitle(
                        metadataTitle = null,
                        path = currentMusic.loc.path,
                        storedTitle = currentMusic.meta.title,
                    ),
                    artist = "",
                )

                val metadata = probeMusicMetadataDirectly(bridge, currentMusic.meta.id)
                if (music.value?.meta?.id != currentMusic.meta.id) {
                    return@collectLatest
                }

                _currentMusicDisplayInfo.value = CurrentMusicDisplayInfo(
                    title = resolveMusicDisplayTitle(
                        metadataTitle = metadata?.title,
                        path = currentMusic.loc.path,
                        storedTitle = currentMusic.meta.title,
                    ),
                    artist = normalizeMusicDisplayArtist(metadata?.artist),
                )
            }
        }
    }

    fun resume() {
        playerControllerRepository.resume()
    }

    fun pause() {
        playerControllerRepository.pause()
    }

    fun stop() {
        playerControllerRepository.stop()
    }

    fun playNext() {
        playerControllerRepository.playNext()
    }

    fun playPrevious() {
        playerControllerRepository.playPrevious()
    }

    fun remove() {
        playerControllerRepository.removeCurrent()
    }

    fun seek(ms: ULong) {
        playerControllerRepository.seek(ms)
    }

    fun play(id: MusicId, playlistId: PlaylistId) {
        playerControllerRepository.play(id, playlistId)
    }

    fun playQueueEntry(queueEntryId: String) {
        playerControllerRepository.playQueueEntry(queueEntryId)
    }

    fun removeQueueEntry(queueEntryId: String) {
        playerControllerRepository.removeQueueEntry(queueEntryId)
    }

    fun commitQueueOrder(orderedQueueEntryIds: List<String>) {
        playerControllerRepository.commitQueueOrder(orderedQueueEntryIds)
    }

    fun changePlayModeToNext() {
        val nextPlayMode = playerRepository.changePlayModeToNext()
        toastRepository.emitToastRes(playModeToastLabelResId(nextPlayMode))
    }

    fun removeLyric() {
        playerRepository.removeLyric()
    }

    fun downloadCurrent() {
        val currentMusic = music.value ?: return
        viewModelScope.launch {
            downloadRepository.enqueueCurrentMusic(currentMusic)
        }
    }

    fun syncPosition() {
        _currentDuration.value = playerControllerRepository.getCurrentPosition().toDuration(
            DurationUnit.MILLISECONDS).toJavaDuration()
        _bufferDuration.value = playerControllerRepository.getBufferedPosition().toDuration(
            DurationUnit.MILLISECONDS).toJavaDuration()
        _totalDuration.value = playerControllerRepository.getDuration()?.toDuration(
            DurationUnit.MILLISECONDS
        )?.toJavaDuration()
    }
}
