package com.kutedev.easemusicplayer.singleton

import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_NEXT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

val DEFAULT_COVER_BASE64 = "data:image/png;base64,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"

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
    val removeAction = _queue.map { queue ->
        if (queue?.context?.type == PlaybackContextType.USER_PLAYLIST) {
            PlaybackRemoveAction.REMOVE_FROM_PLAYLIST
        } else {
            PlaybackRemoveAction.REMOVE_FROM_QUEUE
        }
    }.stateIn(_scope, SharingStarted.Eagerly, PlaybackRemoveAction.REMOVE_FROM_QUEUE)

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

    fun changePlayModeToNext() {
        val nextPlayMode = when (playMode.value) {
            PlayMode.SINGLE -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.LIST
            PlayMode.LIST -> PlayMode.LIST_LOOP
            PlayMode.LIST_LOOP -> PlayMode.SINGLE
        }
        savePlayMode(nextPlayMode)
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
