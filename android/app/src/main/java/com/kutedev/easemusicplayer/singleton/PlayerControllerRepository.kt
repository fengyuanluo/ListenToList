package com.kutedev.easemusicplayer.singleton

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_PLAY_PAUSE
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_STOP
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_NEXT
import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_PREVIOUS
import com.kutedev.easemusicplayer.core.PLAYER_TO_NEXT_COMMAND
import com.kutedev.easemusicplayer.core.PLAYER_TO_PREV_COMMAND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class PlayerControllerRepository @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val playlistRepository: PlaylistRepository,
    private val storageRepository: StorageRepository,
    private val playbackRuntimeKernel: PlaybackRuntimeKernel,
    private val playbackCommandBus: PlaybackCommandBus,
    private val _scope: CoroutineScope,
) {
    private var _mediaController: MediaController? = null
    private val _music = playerRepository.music
    private val _sleep = MutableStateFlow(SleepModeState())
    private var _sleepJob: Job? = null

    val sleepState = _sleep.asStateFlow()

    init {
        _scope.launch(Dispatchers.Main) {
            playlistRepository.preRemovePlaylistEvent.collect { id ->
                val queue = playerRepository.playbackQueueValue()
                if (queue?.context?.type == PlaybackContextType.USER_PLAYLIST && queue.context.playlistId == id) {
                    stop()
                }
            }
        }
        _scope.launch(Dispatchers.Main) {
            storageRepository.preRemoveStorageEvent.collect { id ->
                val queue = playerRepository.playbackQueueValue()
                val targetsCurrentStorage = _music.value?.loc?.storageId == id
                val targetsFolderContext = queue?.context?.type == PlaybackContextType.FOLDER && queue.context.storageId == id
                if (targetsCurrentStorage || targetsFolderContext) {
                    stop()
                }
            }
        }
    }

    fun setupMediaController(mediaController: MediaController) {
        if (_mediaController === mediaController) {
            return
        }
        _mediaController?.release()
        _mediaController = mediaController

        mediaController.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                easeError("media controller error: $error")
            }
        })
        _scope.launch(Dispatchers.Main) {
            playerRepository.reload()
        }
        easeLog("media controller setup")
    }

    fun destroyMediaController() {
        persistCurrentSession()
        _mediaController?.release()
        _mediaController = null
        easeLog("media controller destroy")
    }

    fun hasMediaController(): Boolean {
        return _mediaController != null
    }

    fun getCurrentPosition(): Long {
        return _mediaController?.currentPosition ?: 0L
    }

    fun getBufferedPosition(): Long {
        return _mediaController?.bufferedPosition ?: 0L
    }

    fun getDuration(): Long? {
        val duration = _mediaController?.duration ?: return null
        if (duration == C.TIME_UNSET || duration <= 0) {
            return null
        }
        return duration
    }

    fun play(id: MusicId, playlistId: PlaylistId, direction: Int = PLAY_DIRECTION_NEXT) {
        val activeQueue = playerRepository.playbackQueueValue()
        if (
            activeQueue?.context?.type == PlaybackContextType.USER_PLAYLIST &&
            activeQueue.context.playlistId == playlistId &&
            playerRepository.currentQueueEntryIdValue() == buildPlaylistQueueEntryId(playlistId, id)
        ) {
            resume()
            return
        }
        playbackCommandBus.dispatch(
            PlaybackCommand.PlayPlaylistMusic(
                playlistId = playlistId,
                musicId = id,
                direction = direction,
            )
        )
    }

    fun playQueueEntry(queueEntryId: String) {
        val queue = playerRepository.playbackQueueValue() ?: return
        if (queueEntryId == (playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId)) {
            resume()
            return
        }
        playbackCommandBus.dispatch(PlaybackCommand.PlayQueueEntry(queueEntryId))
    }

    fun removeQueueEntry(queueEntryId: String) {
        playbackCommandBus.dispatch(PlaybackCommand.RemoveQueueEntry(queueEntryId))
    }

    fun commitQueueOrder(orderedQueueEntryIds: List<String>) {
        playbackCommandBus.dispatch(
            PlaybackCommand.CommitQueueOrder(
                orderedQueueEntryIds = orderedQueueEntryIds.toList(),
            )
        )
    }

    fun playFolder(
        storageId: StorageId,
        folderPath: String,
        songs: List<StorageEntry>,
        targetEntryPath: String,
        ensuredMusics: List<AddedMusic>? = null,
    ) {
        playbackCommandBus.dispatch(
            PlaybackCommand.PlayFolder(
                storageId = storageId,
                folderPath = folderPath,
                songs = songs,
                targetEntryPath = targetEntryPath,
                ensuredMusics = ensuredMusics,
            )
        )
    }

    fun appendEntriesToQueue(entries: List<StorageEntry>) {
        playbackCommandBus.dispatch(PlaybackCommand.AppendEntries(entries))
    }

    fun resume() {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
            mediaController.play()
            playbackRuntimeKernel.persistCurrentSession(mediaController)
        } else {
            easeError("media controller resume failed, command COMMAND_PLAY_PAUSE is unavailable")
        }
    }

    fun pause() {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
            mediaController.pause()
            playbackRuntimeKernel.persistCurrentSession(
                positionMs = mediaController.currentPosition,
                playWhenReady = false,
            )
        } else {
            easeError("media controller pause failed, command COMMAND_PLAY_PAUSE is unavailable")
        }
    }

    fun stop() {
        val mediaController = _mediaController
        if (mediaController != null) {
            if (mediaController.isCommandAvailable(COMMAND_STOP)) {
                mediaController.stop()
            } else {
                easeError("media controller stop failed, command COMMAND_STOP is unavailable")
            }
        }

        playbackRuntimeKernel.clearPlaybackState()
    }

    fun playNext() {
        _mediaController?.sendCustomCommand(
            SessionCommand(PLAYER_TO_NEXT_COMMAND, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    fun playPrevious() {
        _mediaController?.sendCustomCommand(
            SessionCommand(PLAYER_TO_PREV_COMMAND, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    fun seek(ms: ULong) {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            mediaController.seekTo(ms.toLong())
            playbackRuntimeKernel.persistCurrentSession(
                positionMs = ms.toLong(),
                playWhenReady = mediaController.playWhenReady,
            )
        } else {
            easeError("media controller seek failed, command COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM is unavailable")
        }
    }

    fun scheduleSleep(newExpiredMs: Long) {
        _sleepJob?.cancel()

        val delayMs = max(newExpiredMs - System.currentTimeMillis(), 0)
        _sleepJob = _scope.launch {
            _sleep.update { state -> state.copy(enabled = true, expiredMs = newExpiredMs) }
            easeLog("schedule sleep")
            delay(delayMs)
            easeLog("sleep scheduled")
            playerRepository.emitPauseRequest()
            _sleep.update { state -> state.copy(enabled = false, expiredMs = 0) }
        }
    }

    fun refreshPlaylistIfMatch(playlist: Playlist) {
        playbackCommandBus.dispatch(PlaybackCommand.RefreshPlaylistIfMatch(playlist))
    }

    fun cancelSleep() {
        _sleepJob?.cancel()
        _sleepJob = null
        _sleep.update { state -> state.copy(enabled = false, expiredMs = 0) }
    }

    fun removeCurrent() {
        playbackCommandBus.dispatch(PlaybackCommand.RemoveCurrent)
    }

    private fun persistCurrentSession(
        positionOverrideMs: Long? = null,
        playWhenReadyOverride: Boolean? = null,
        currentQueueEntryIdOverride: String? = null,
    ) {
        val mediaController = _mediaController
        playbackRuntimeKernel.persistCurrentSession(
            positionMs = positionOverrideMs ?: mediaController?.currentPosition ?: 0L,
            playWhenReady = playWhenReadyOverride ?: mediaController?.playWhenReady ?: playerRepository.playing.value,
            currentQueueEntryIdOverride = currentQueueEntryIdOverride,
        )
    }
}
