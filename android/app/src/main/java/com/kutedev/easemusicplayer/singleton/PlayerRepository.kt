package com.kutedev.easemusicplayer.singleton

import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_NEXT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.ArgUpdateMusicLyric
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.ctUpdateMusicLyric
import uniffi.ease_client_backend.ctsGetPreferencePlaymode
import uniffi.ease_client_backend.ctsSavePreferencePlaymode
import uniffi.ease_client_schema.PlayMode
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton


data class SleepModeState(
    val enabled: Boolean = false,
    val expiredMs: Long = 0
)

data class PlaybackRecoverySeed(
    val token: Long,
    val queueEntryId: String,
    val direction: Int,
)

@Singleton
class PlayerRepository @Inject constructor(
    private val bridge: Bridge,
    private val _scope: CoroutineScope
) {
    private val _music = MutableStateFlow(null as Music?)
    private val _playlist = MutableStateFlow(null as Playlist?)
    private val _queue = MutableStateFlow(null as PlaybackQueueSnapshot?)
    private val _currentQueueEntryId = MutableStateFlow(null as String?)
    private val _playing = MutableStateFlow(false)
    private val _loading = MutableStateFlow(false)
    private val _durationChanged = MutableSharedFlow<Unit>()
    private val _playMode = MutableStateFlow(PlayMode.SINGLE)
    private val _pauseRequest = MutableSharedFlow<Unit>()
    private val _recoverySeed = MutableStateFlow<PlaybackRecoverySeed?>(null)
    private val recoveryToken = AtomicLong(0L)

    private val _currentQueueIndex = combine(_queue, _currentQueueEntryId) { queue, queueEntryId ->
        queue?.indexOf(queueEntryId) ?: -1
    }.stateIn(_scope, SharingStarted.Eagerly, -1)

    val playMode = _playMode.asStateFlow()
    val durationChanged = _durationChanged.asSharedFlow()
    val music = _music.asStateFlow()
    val playlist = _playlist.asStateFlow()
    val playbackQueue = _queue.asStateFlow()
    val currentQueueEntryId = _currentQueueEntryId.asStateFlow()
    val playing = _playing.asStateFlow()
    val loading = _loading.asStateFlow()
    val pauseRequest = _pauseRequest.asSharedFlow()
    val recoverySeed = _recoverySeed.asStateFlow()
    val currentQueueEntry = combine(_queue, _currentQueueEntryId) { queue, queueEntryId ->
        queue?.entries?.firstOrNull { it.queueEntryId == queueEntryId }
    }.stateIn(_scope, SharingStarted.Eagerly, null)
    val previousMusic = combine(playMode, _currentQueueIndex, _queue) { playMode, queueIndex, queue ->
        val entries = queue?.entries.orEmpty()
        if (queueIndex == -1 || entries.isEmpty()) {
            null
        } else if (queueIndex == 0 && (playMode == PlayMode.SINGLE || playMode == PlayMode.LIST)) {
            null
        } else {
            val i = (queueIndex + entries.size - 1) % entries.size
            entries[i].musicAbstract
        }
    }.stateIn(_scope, SharingStarted.Eagerly, null)

    val nextMusic = combine(playMode, _currentQueueIndex, _queue) { playMode, queueIndex, queue ->
        val entries = queue?.entries.orEmpty()
        if (queueIndex == -1 || entries.isEmpty()) {
            null
        } else if (queueIndex == entries.lastIndex && (playMode == PlayMode.SINGLE || playMode == PlayMode.LIST)) {
            null
        } else {
            val i = (queueIndex + 1) % entries.size
            entries[i].musicAbstract
        }
    }.stateIn(_scope, SharingStarted.Eagerly, null)

    val onCompleteMusic = combine(playMode, _currentQueueIndex, _queue) { playMode, queueIndex, queue ->
        val entries = queue?.entries.orEmpty()
        if (queueIndex == -1 || entries.isEmpty()) {
            null
        } else if (playMode == PlayMode.SINGLE || (queueIndex == entries.lastIndex && playMode == PlayMode.LIST)) {
            null
        } else if (playMode == PlayMode.SINGLE_LOOP) {
            entries[queueIndex].musicAbstract
        } else {
            val i = (queueIndex + 1) % entries.size
            entries[i].musicAbstract
        }
    }.stateIn(_scope, SharingStarted.Eagerly, null)

    fun setIsPlaying(playing: Boolean) {
        _playing.value = playing
    }

    fun setIsLoading(loading: Boolean) {
        _loading.value = loading
    }

    fun notifyDurationChanged() {
        _scope.launch {
            _durationChanged.emit(Unit)
        }
    }

    fun setPlaybackSession(
        music: Music,
        queueSnapshot: PlaybackQueueSnapshot,
        currentQueueEntryId: String,
        playlist: Playlist? = null,
    ) {
        _music.value = music
        val nextQueue = normalizeQueueSnapshot(queueSnapshot, currentQueueEntryId)
        _queue.value = nextQueue
        _currentQueueEntryId.value = nextQueue.currentQueueEntryId
        _playlist.value = playlist
    }

    fun updateCurrentMusic(music: Music) {
        val current = _music.value ?: return
        if (current.meta.id == music.meta.id) {
            _music.value = music
        }
    }

    fun updateCurrentQueueEntry(
        queueEntryId: String,
        music: Music,
    ) {
        val queue = _queue.value ?: return
        if (queue.entries.none { it.queueEntryId == queueEntryId }) {
            return
        }
        _queue.value = queue.copy(currentQueueEntryId = queueEntryId)
        _currentQueueEntryId.value = queueEntryId
        _music.value = music
    }

    fun updatePlaybackQueue(
        queueSnapshot: PlaybackQueueSnapshot,
        currentQueueEntryId: String,
        currentMusic: Music? = null,
        playlist: Playlist? = _playlist.value,
    ) {
        val nextQueue = normalizeQueueSnapshot(queueSnapshot, currentQueueEntryId)
        _queue.value = nextQueue
        _currentQueueEntryId.value = nextQueue.currentQueueEntryId
        if (currentMusic != null) {
            _music.value = currentMusic
        }
        _playlist.value = playlist
    }

    fun resetCurrent() {
        _music.value = null
        _playlist.value = null
        _queue.value = null
        _currentQueueEntryId.value = null
    }

    fun seedPlaybackRecovery(
        queueEntryId: String,
        direction: Int = PLAY_DIRECTION_NEXT
    ) {
        _recoverySeed.value = PlaybackRecoverySeed(
            token = recoveryToken.incrementAndGet(),
            queueEntryId = queueEntryId,
            direction = direction,
        )
    }

    fun changePlayModeToNext(): PlayMode {
        val nextPlayMode = when (playMode.value) {
            PlayMode.SINGLE -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.LIST
            PlayMode.LIST -> PlayMode.LIST_LOOP
            PlayMode.LIST_LOOP -> PlayMode.SINGLE
        }
        savePlayMode(nextPlayMode)
        return nextPlayMode
    }

    fun removeLyric() {
        val m = _music.value ?: return
        _scope.launch {
            bridge.run { ctUpdateMusicLyric(it, ArgUpdateMusicLyric(id = m.meta.id, lyricLoc = null)) }
            reload()
        }
    }

    private fun savePlayMode(playMode: PlayMode) {
        bridge.runSync { ctsSavePreferencePlaymode(it, playMode) }
        reload()
    }

    fun setCurrentSourcePlaylist(playlist: Playlist?) {
        _playlist.value = playlist
    }

    fun currentQueueEntryIdValue(): String? {
        return _currentQueueEntryId.value
    }

    fun playbackQueueValue(): PlaybackQueueSnapshot? {
        return _queue.value
    }

    fun currentQueueIndexValue(): Int {
        return _currentQueueIndex.value
    }

    fun emitPauseRequest() {
        _scope.launch {
            _pauseRequest.emit(Unit)
        }
    }

    fun reload() {
        bridge.runSync { ctsGetPreferencePlaymode(it) }?.let { _playMode.value = it }
        _scope.launch {
            _durationChanged.emit(Unit)
        }
    }

    private fun normalizeQueueSnapshot(
        queueSnapshot: PlaybackQueueSnapshot,
        currentQueueEntryId: String,
    ): PlaybackQueueSnapshot {
        val resolvedQueueEntryId = queueSnapshot.entries
            .firstOrNull { it.queueEntryId == currentQueueEntryId }
            ?.queueEntryId
            ?: queueSnapshot.currentQueueEntryId
        return queueSnapshot.copy(currentQueueEntryId = resolvedQueueEntryId)
    }
}
