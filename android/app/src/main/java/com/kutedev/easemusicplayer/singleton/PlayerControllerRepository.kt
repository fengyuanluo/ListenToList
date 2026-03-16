package com.kutedev.easemusicplayer.singleton

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_PLAY_PAUSE
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_STOP
import androidx.media3.session.MediaController
import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_NEXT
import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_PREVIOUS
import com.kutedev.easemusicplayer.core.playQueueUtil
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.ArgReorderMusic
import uniffi.ease_client_backend.ArgEnsureMusics
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ToAddMusicEntry
import uniffi.ease_client_backend.ctEnsureMusics
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctGetPlaylist
import uniffi.ease_client_backend.ctsGetMusicAbstract
import uniffi.ease_client_backend.ctsReorderMusicInPlaylist
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageId
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class PlayerControllerRepository @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val toastRepository: ToastRepository,
    private val playlistRepository: PlaylistRepository,
    private val storageRepository: StorageRepository,
    private val bridge: Bridge,
    private val playbackSessionStore: PlaybackSessionStore,
    private val _scope: CoroutineScope
) {
    private var _mediaController: MediaController? = null
    private val _playlist = playerRepository.playlist
    private val _music = playerRepository.music
    private val _sleep = MutableStateFlow(SleepModeState())

    private var _sleepJob: Job? = null
    private val nextMusic = playerRepository.nextMusic
    private val previousMusic = playerRepository.previousMusic

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
            restorePersistedSessionIfNeeded()
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
        return _mediaController?.currentPosition ?: 0
    }

    fun getBufferedPosition(): Long {
        return _mediaController?.bufferedPosition ?: 0
    }

    fun getDuration(): Long? {
        val duration = _mediaController?.duration ?: return null
        if (duration == C.TIME_UNSET || duration <= 0) {
            return null
        }
        return duration
    }

    fun play(id: MusicId, playlistId: PlaylistId, direction: Int = PLAY_DIRECTION_NEXT) {
        val mediaController = _mediaController ?: return

        val activeQueue = playerRepository.playbackQueueValue()
        if (
            activeQueue?.context?.type == PlaybackContextType.USER_PLAYLIST &&
            activeQueue.context.playlistId == playlistId &&
            playerRepository.currentQueueEntryIdValue() == buildPlaylistQueueEntryId(playlistId, id)
        ) {
            resume()
            return
        }

        _scope.launch(Dispatchers.Main) {
            val playlist = bridge.run { ctGetPlaylist(it, playlistId) }
            if (playlist == null) {
                stop()
                return@launch
            }

            val snapshot = buildPlaylistSnapshot(playlist, buildPlaylistQueueEntryId(playlistId, id)) ?: run {
                toastRepository.emitToast("歌曲资源不可用")
                stop()
                return@launch
            }
            val target = bridge.run { ctGetMusic(it, id) }
            if (target == null) {
                toastRepository.emitToast("歌曲资源不可用")
                stop()
                return@launch
            }
            playResolvedQueue(
                player = mediaController,
                snapshot = snapshot,
                currentMusic = target,
                currentQueueEntryId = snapshot.currentQueueEntryId,
                direction = direction,
                sourcePlaylist = playlist,
            )
        }
    }

    fun playQueueEntry(queueEntryId: String) {
        val queue = playerRepository.playbackQueueValue() ?: return
        if (queueEntryId == (playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId)) {
            resume()
            return
        }
        val targetIndex = queue.indexOf(queueEntryId)
        if (targetIndex < 0) {
            return
        }
        val currentIndex = playerRepository.currentQueueIndexValue()
        val direction = when {
            currentIndex < 0 -> PLAY_DIRECTION_NEXT
            targetIndex < currentIndex -> PLAY_DIRECTION_PREVIOUS
            else -> PLAY_DIRECTION_NEXT
        }
        queue.entries.getOrNull(targetIndex)?.let { entry ->
            playQueueEntry(entry, queue, direction)
        }
    }

    fun removeQueueEntry(queueEntryId: String) {
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        if (queueEntryId == currentEntryId) {
            removeCurrent()
            return
        }
        when (queue.context.type) {
            PlaybackContextType.USER_PLAYLIST -> removeNonCurrentPlaylistEntry(queue, queueEntryId)
            PlaybackContextType.FOLDER,
            PlaybackContextType.TEMPORARY -> removeNonCurrentQueueEntry(queue, queueEntryId)
        }
    }

    fun moveQueueEntry(fromIndex: Int, toIndex: Int) {
        val queue = playerRepository.playbackQueueValue() ?: return
        if (
            fromIndex == toIndex ||
            fromIndex !in queue.entries.indices ||
            toIndex !in queue.entries.indices
        ) {
            return
        }
        val reorderedEntries = queue.entries.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        val nextSnapshot = queue.copy(
            entries = reorderedEntries,
            currentQueueEntryId = currentEntryId,
        )
        when (queue.context.type) {
            PlaybackContextType.USER_PLAYLIST -> {
                val playlistId = queue.context.playlistId ?: return
                val moved = reorderedEntries.getOrNull(toIndex) ?: return
                val prev = reorderedEntries.getOrNull(toIndex - 1)
                val next = reorderedEntries.getOrNull(toIndex + 1)
                _scope.launch {
                    bridge.runSync {
                        ctsReorderMusicInPlaylist(
                            it,
                            ArgReorderMusic(
                                playlistId = playlistId,
                                id = moved.musicId,
                                a = prev?.musicId,
                                b = next?.musicId,
                            )
                        )
                    }
                    playlistRepository.scheduleReload()
                }
                applyEditedQueueSnapshot(
                    snapshot = nextSnapshot,
                    requestedQueueEntryId = currentEntryId,
                    sourcePlaylist = buildUpdatedSourcePlaylist(_playlist.value, reorderedEntries),
                )
            }

            PlaybackContextType.FOLDER -> {
                applyEditedQueueSnapshot(
                    snapshot = nextSnapshot,
                    requestedQueueEntryId = currentEntryId,
                )
            }

            PlaybackContextType.TEMPORARY -> {
                applyEditedQueueSnapshot(
                    snapshot = nextSnapshot.copy(context = PlaybackContext(type = PlaybackContextType.TEMPORARY)),
                    requestedQueueEntryId = currentEntryId,
                )
            }
        }
    }

    fun playFolder(
        storageId: StorageId,
        folderPath: String,
        songs: List<StorageEntry>,
        targetEntryPath: String,
        ensuredMusics: List<AddedMusic>? = null,
    ) {
        val mediaController = _mediaController ?: return
        _scope.launch(Dispatchers.Main) {
            val ensured = ensuredMusics ?: bridge.run {
                ctEnsureMusics(
                    it,
                    ArgEnsureMusics(
                        entries = songs.map { song -> ToAddMusicEntry(song, song.name) },
                    )
                )
            } ?: emptyList()
            if (ensured.isEmpty()) {
                toastRepository.emitToast("当前文件夹暂无可播放的音乐")
                return@launch
            }
            playlistRepository.requestTotalDuration(ensured)
            val context = PlaybackContext(
                type = PlaybackContextType.FOLDER,
                storageId = storageId,
                folderPath = folderPath,
            )
            val targetMusicId = songs
                .indexOfFirst { it.path == targetEntryPath }
                .takeIf { it >= 0 }
                ?.let { ensured.getOrNull(it)?.id }
            val entries = buildList {
                ensured.forEachIndexed { index, added ->
                    val musicAbstract = bridge.runSync { backend -> ctsGetMusicAbstract(backend, added.id) } ?: return@forEachIndexed
                    add(
                        PlaybackQueueEntry(
                            queueEntryId = buildFolderQueueEntryId(storageId, folderPath, added.id, index),
                            musicId = added.id,
                            musicAbstract = musicAbstract,
                            sourceContext = context,
                        )
                    )
                }
            }
            if (entries.isEmpty()) {
                toastRepository.emitToast("当前文件夹暂无可播放的音乐")
                return@launch
            }
            val targetEntry = entries.firstOrNull { it.musicId == targetMusicId } ?: entries.first()
            val target = bridge.run { ctGetMusic(it, targetEntry.musicId) }
            if (target == null) {
                toastRepository.emitToast("歌曲资源不可用")
                stop()
                return@launch
            }
            playResolvedQueue(
                player = mediaController,
                snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = targetEntry.queueEntryId,
                ),
                currentMusic = target,
                currentQueueEntryId = targetEntry.queueEntryId,
            )
        }
    }

    fun appendEntriesToQueue(entries: List<StorageEntry>) {
        val mediaController = _mediaController
        val songs = entries
            .filter { entry -> entry.entryTyp() == StorageEntryType.MUSIC }
            .distinctBy { entry -> "${entry.storageId.value}:${entry.path}" }
        if (songs.isEmpty()) {
            toastRepository.emitToast("当前没有可加入播放队列的音乐")
            return
        }

        _scope.launch(Dispatchers.Main) {
            val ensured = bridge.run {
                ctEnsureMusics(
                    it,
                    ArgEnsureMusics(
                        entries = songs.map { song -> ToAddMusicEntry(song, song.name) },
                    )
                )
            } ?: emptyList()
            if (ensured.isEmpty()) {
                toastRepository.emitToast("当前没有可加入播放队列的音乐")
                return@launch
            }
            playlistRepository.requestTotalDuration(ensured)
            val appendedEntries = buildTemporaryEntries(songs = songs, ensured = ensured)
            if (appendedEntries.isEmpty()) {
                toastRepository.emitToast("当前没有可加入播放队列的音乐")
                return@launch
            }

            val activeQueue = playerRepository.playbackQueueValue()
            val activeMusic = playerRepository.music.value
            if (activeQueue == null || activeMusic == null) {
                val targetEntry = appendedEntries.first()
                val targetMusic = bridge.run { ctGetMusic(it, targetEntry.musicId) } ?: run {
                    toastRepository.emitToast("歌曲资源不可用")
                    return@launch
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = PlaybackContext(type = PlaybackContextType.TEMPORARY),
                    entries = appendedEntries,
                    currentQueueEntryId = targetEntry.queueEntryId,
                )
                if (mediaController != null) {
                    playResolvedQueue(
                        player = mediaController,
                        snapshot = snapshot,
                        currentMusic = targetMusic,
                        currentQueueEntryId = targetEntry.queueEntryId,
                        playWhenReady = false,
                    )
                } else {
                    playerRepository.setPlaybackSession(
                        music = targetMusic,
                        queueSnapshot = snapshot,
                        currentQueueEntryId = targetEntry.queueEntryId,
                    )
                    persistCurrentSession(
                        playWhenReadyOverride = false,
                        currentQueueEntryIdOverride = targetEntry.queueEntryId,
                    )
                }
                toastRepository.emitToast("已加入播放队列")
                return@launch
            }

            val currentQueueEntryId = playerRepository.currentQueueEntryIdValue() ?: activeQueue.currentQueueEntryId
            val combinedSnapshot = PlaybackQueueSnapshot(
                context = PlaybackContext(type = PlaybackContextType.TEMPORARY),
                entries = activeQueue.entries + appendedEntries,
                currentQueueEntryId = currentQueueEntryId,
            )
            if (mediaController != null) {
                playResolvedQueue(
                    player = mediaController,
                    snapshot = combinedSnapshot,
                    currentMusic = activeMusic,
                    currentQueueEntryId = currentQueueEntryId,
                    startPositionMs = getCurrentPosition(),
                    playWhenReady = mediaController.playWhenReady,
                )
            } else {
                playerRepository.setPlaybackSession(
                    music = activeMusic,
                    queueSnapshot = combinedSnapshot,
                    currentQueueEntryId = currentQueueEntryId,
                )
                persistCurrentSession(
                    positionOverrideMs = getCurrentPosition(),
                    playWhenReadyOverride = playerRepository.playing.value,
                    currentQueueEntryIdOverride = currentQueueEntryId,
                )
            }
            toastRepository.emitToast("已加入播放队列")
        }
    }

    fun resume() {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
            mediaController.play()
            persistCurrentSession()
        } else {
            easeError("media controller resume failed, command COMMAND_PLAY_PAUSE is unavailable")
        }
    }

    fun pause() {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
            mediaController.pause()
            persistCurrentSession(playWhenReadyOverride = false)
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

        playerRepository.resetCurrent()
        playbackSessionStore.clear()
    }

    fun playNext() {
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (queue.entries.isEmpty() || currentIndex < 0) {
            return
        }
        if (currentIndex == queue.entries.lastIndex && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
            return
        }
        if (seekAdjacentMediaItem(PLAY_DIRECTION_NEXT)) {
            return
        }
        val nextIndex = (currentIndex + 1) % queue.entries.size
        queue.entries.getOrNull(nextIndex)?.let { entry ->
            playQueueEntry(entry, queue, PLAY_DIRECTION_NEXT)
        }
    }

    fun playPrevious() {
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (queue.entries.isEmpty() || currentIndex < 0) {
            return
        }
        if (currentIndex == 0 && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
            return
        }
        if (seekAdjacentMediaItem(PLAY_DIRECTION_PREVIOUS)) {
            return
        }
        val previousIndex = (currentIndex + queue.entries.size - 1) % queue.entries.size
        queue.entries.getOrNull(previousIndex)?.let { entry ->
            playQueueEntry(entry, queue, PLAY_DIRECTION_PREVIOUS)
        }
    }

    fun seek(ms: ULong) {
        val mediaController = _mediaController ?: return

        if (mediaController.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            mediaController.seekTo(ms.toLong())
            persistCurrentSession(positionOverrideMs = ms.toLong())
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
        val activeQueue = playerRepository.playbackQueueValue() ?: return
        if (activeQueue.context.type != PlaybackContextType.USER_PLAYLIST || activeQueue.context.playlistId != playlist.abstr.meta.id) {
            return
        }
        val mediaController = _mediaController
        val requestedQueueEntryId = playerRepository.currentQueueEntryIdValue() ?: activeQueue.currentQueueEntryId
        val refreshedSnapshot = buildPlaylistSnapshot(playlist, requestedQueueEntryId) ?: run {
            stop()
            return
        }
        val oldIndex = activeQueue.indexOf(requestedQueueEntryId)
        val nextEntry = refreshedSnapshot.entries.firstOrNull { it.queueEntryId == requestedQueueEntryId }
            ?: refreshedSnapshot.entries.getOrNull(oldIndex)
            ?: refreshedSnapshot.entries.lastOrNull()
            ?: run {
                stop()
                return
            }
        _scope.launch(Dispatchers.Main) {
            val currentMusic = bridge.run { ctGetMusic(it, nextEntry.musicId) } ?: run {
                stop()
                return@launch
            }
            val preservePosition = currentMusic.meta.id == playerRepository.music.value?.meta?.id
            val startPositionMs = if (preservePosition) getCurrentPosition() else 0L
            val playWhenReady = mediaController?.playWhenReady ?: playerRepository.playing.value
            val nextSnapshot = refreshedSnapshot.copy(currentQueueEntryId = nextEntry.queueEntryId)
            if (mediaController != null) {
                playResolvedQueue(
                    player = mediaController,
                    snapshot = nextSnapshot,
                    currentMusic = currentMusic,
                    currentQueueEntryId = nextEntry.queueEntryId,
                    startPositionMs = startPositionMs,
                    playWhenReady = playWhenReady,
                    sourcePlaylist = playlist,
                )
            } else {
                playerRepository.setPlaybackSession(
                    music = currentMusic,
                    queueSnapshot = nextSnapshot,
                    currentQueueEntryId = nextEntry.queueEntryId,
                    playlist = playlist,
                )
            }
        }
    }

    fun cancelSleep() {
        _sleepJob?.cancel()
        _sleepJob = null
        _sleep.update { state -> state.copy(enabled = false, expiredMs = 0) }
    }

    fun removeCurrent() {
        val queue = playerRepository.playbackQueueValue() ?: return
        when (queue.context.type) {
            PlaybackContextType.USER_PLAYLIST -> removeCurrentFromPlaylist(queue)
            PlaybackContextType.FOLDER,
            PlaybackContextType.TEMPORARY -> removeCurrentFromQueue(queue)
        }
    }

    private fun removeNonCurrentPlaylistEntry(
        queue: PlaybackQueueSnapshot,
        queueEntryId: String,
    ) {
        val playlistId = queue.context.playlistId ?: return
        val nextEntries = queue.entries.filterNot { it.queueEntryId == queueEntryId }
        if (nextEntries.isEmpty()) {
            stop()
            return
        }
        val target = queue.entries.firstOrNull { it.queueEntryId == queueEntryId } ?: return
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        val nextSnapshot = queue.copy(
            entries = nextEntries,
            currentQueueEntryId = currentEntryId,
        )
        _scope.launch(Dispatchers.Main) {
            playlistRepository.removeMusic(playlistId, target.musicId)
        }
        applyEditedQueueSnapshot(
            snapshot = nextSnapshot,
            requestedQueueEntryId = currentEntryId,
            sourcePlaylist = buildUpdatedSourcePlaylist(_playlist.value, nextEntries),
        )
    }

    private fun removeNonCurrentQueueEntry(
        queue: PlaybackQueueSnapshot,
        queueEntryId: String,
    ) {
        val nextEntries = queue.entries.filterNot { it.queueEntryId == queueEntryId }
        if (nextEntries.isEmpty()) {
            stop()
            return
        }
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        val nextSnapshot = queue.copy(
            entries = nextEntries,
            currentQueueEntryId = currentEntryId,
        )
        applyEditedQueueSnapshot(
            snapshot = nextSnapshot,
            requestedQueueEntryId = currentEntryId,
        )
    }

    private fun removeCurrentFromPlaylist(queue: PlaybackQueueSnapshot) {
        val playlistId = queue.context.playlistId ?: return
        val currentEntry = queue.entries.firstOrNull {
            it.queueEntryId == (playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId)
        } ?: return
        val currentIndex = queue.indexOf(currentEntry.queueEntryId)
        val wasPlaying = _mediaController?.playWhenReady ?: playerRepository.playing.value
        _scope.launch(Dispatchers.Main) {
            playlistRepository.removeMusic(playlistId, currentEntry.musicId)
            val playlist = bridge.run { ctGetPlaylist(it, playlistId) } ?: run {
                stop()
                return@launch
            }
            val refreshedSnapshot = buildPlaylistSnapshot(playlist, currentEntry.queueEntryId)
            if (refreshedSnapshot == null || refreshedSnapshot.entries.isEmpty()) {
                stop()
                return@launch
            }
            val nextEntry = refreshedSnapshot.entries.getOrNull(currentIndex)
                ?: refreshedSnapshot.entries.lastOrNull()
                ?: run {
                    stop()
                    return@launch
                }
            val nextMusic = bridge.run { ctGetMusic(it, nextEntry.musicId) } ?: run {
                stop()
                return@launch
            }
            val player = _mediaController ?: run {
                playerRepository.setPlaybackSession(
                    music = nextMusic,
                    queueSnapshot = refreshedSnapshot.copy(currentQueueEntryId = nextEntry.queueEntryId),
                    currentQueueEntryId = nextEntry.queueEntryId,
                    playlist = playlist,
                )
                return@launch
            }
            playResolvedQueue(
                player = player,
                snapshot = refreshedSnapshot.copy(currentQueueEntryId = nextEntry.queueEntryId),
                currentMusic = nextMusic,
                currentQueueEntryId = nextEntry.queueEntryId,
                playWhenReady = wasPlaying,
                sourcePlaylist = playlist,
            )
        }
    }

    private fun removeCurrentFromQueue(queue: PlaybackQueueSnapshot) {
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: return
        val currentIndex = queue.indexOf(currentEntryId)
        if (currentIndex < 0) {
            return
        }
        val nextEntries = queue.entries.filterNot { it.queueEntryId == currentEntryId }
        if (nextEntries.isEmpty()) {
            stop()
            return
        }
        val nextIndex = currentIndex.coerceAtMost(nextEntries.lastIndex)
        val nextEntry = nextEntries[nextIndex]
        val nextSnapshot = queue.copy(entries = nextEntries, currentQueueEntryId = nextEntry.queueEntryId)
        val wasPlaying = _mediaController?.playWhenReady ?: playerRepository.playing.value
        _scope.launch(Dispatchers.Main) {
            val nextMusic = bridge.run { ctGetMusic(it, nextEntry.musicId) } ?: run {
                stop()
                return@launch
            }
            val player = _mediaController ?: run {
                playerRepository.setPlaybackSession(
                    music = nextMusic,
                    queueSnapshot = nextSnapshot,
                    currentQueueEntryId = nextEntry.queueEntryId,
                )
                return@launch
            }
            playResolvedQueue(
                player = player,
                snapshot = nextSnapshot,
                currentMusic = nextMusic,
                currentQueueEntryId = nextEntry.queueEntryId,
                playWhenReady = wasPlaying,
            )
        }
    }

    private fun seekAdjacentMediaItem(direction: Int): Boolean {
        val mediaController = _mediaController ?: return false
        val queue = playerRepository.playbackQueueValue() ?: return false
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (currentIndex < 0 || queue.entries.isEmpty()) {
            return false
        }
        val targetIndex = if (direction >= 0) {
            if (currentIndex == queue.entries.lastIndex && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
                return false
            }
            (currentIndex + 1) % queue.entries.size
        } else {
            if (currentIndex == 0 && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
                return false
            }
            (currentIndex + queue.entries.size - 1) % queue.entries.size
        }
        val target = queue.entries.getOrNull(targetIndex) ?: return false

        val command = if (direction >= 0) {
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        } else {
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        }
        if (!mediaController.isCommandAvailable(command)) {
            return false
        }
        playerRepository.seedPlaybackRecovery(target.queueEntryId, direction)
        if (direction >= 0) {
            mediaController.seekToNextMediaItem()
        } else {
            mediaController.seekToPreviousMediaItem()
        }
        mediaController.play()
        persistCurrentSession(
            playWhenReadyOverride = true,
            currentQueueEntryIdOverride = target.queueEntryId,
        )
        return true
    }

    private fun playQueueEntry(
        entry: PlaybackQueueEntry,
        queue: PlaybackQueueSnapshot,
        direction: Int,
    ) {
        val player = _mediaController ?: return
        _scope.launch(Dispatchers.Main) {
            val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return@launch
            playResolvedQueue(
                player = player,
                snapshot = queue.copy(currentQueueEntryId = entry.queueEntryId),
                currentMusic = music,
                currentQueueEntryId = entry.queueEntryId,
                direction = direction,
                sourcePlaylist = if (queue.context.type == PlaybackContextType.USER_PLAYLIST) _playlist.value else null,
            )
        }
    }

    private fun buildUpdatedSourcePlaylist(
        sourcePlaylist: Playlist?,
        entries: List<PlaybackQueueEntry>,
    ): Playlist? {
        val source = sourcePlaylist ?: return null
        return source.copy(
            abstr = source.abstr.copy(
                musicCount = entries.size.toULong(),
            ),
            musics = entries.map { entry -> entry.musicAbstract },
        )
    }

    private fun buildTemporaryEntries(
        songs: List<StorageEntry>,
        ensured: List<AddedMusic>,
    ): List<PlaybackQueueEntry> {
        val nonce = System.currentTimeMillis()
        return buildList {
            ensured.forEachIndexed { index, added ->
                val song = songs.getOrNull(index) ?: return@forEachIndexed
                val musicAbstract = bridge.runSync { backend -> ctsGetMusicAbstract(backend, added.id) } ?: return@forEachIndexed
                add(
                    PlaybackQueueEntry(
                        queueEntryId = buildTemporaryQueueEntryId(
                            storageId = song.storageId,
                            path = song.path,
                            musicId = added.id,
                            nonce = nonce,
                            index = index,
                        ),
                        musicId = added.id,
                        musicAbstract = musicAbstract,
                        sourceContext = PlaybackContext(
                            type = PlaybackContextType.TEMPORARY,
                            storageId = song.storageId,
                            folderPath = song.path.substringBeforeLast('/', "/"),
                        ),
                    )
                )
            }
        }
    }

    private fun applyEditedQueueSnapshot(
        snapshot: PlaybackQueueSnapshot,
        requestedQueueEntryId: String,
        sourcePlaylist: Playlist? = if (snapshot.context.type == PlaybackContextType.USER_PLAYLIST) _playlist.value else null,
    ) {
        if (snapshot.entries.isEmpty()) {
            stop()
            return
        }
        val targetEntry = snapshot.entries.firstOrNull { it.queueEntryId == requestedQueueEntryId }
            ?: snapshot.currentEntry()
            ?: snapshot.entries.firstOrNull()
            ?: run {
                stop()
                return
            }
        val player = _mediaController
        val wasPlaying = player?.playWhenReady ?: playerRepository.playing.value
        _scope.launch(Dispatchers.Main) {
            val currentMusic = bridge.run { ctGetMusic(it, targetEntry.musicId) } ?: run {
                stop()
                return@launch
            }
            val preservePosition = currentMusic.meta.id == playerRepository.music.value?.meta?.id
            val startPositionMs = if (preservePosition) getCurrentPosition() else 0L
            val finalSnapshot = snapshot.copy(currentQueueEntryId = targetEntry.queueEntryId)
            if (player != null) {
                playResolvedQueue(
                    player = player,
                    snapshot = finalSnapshot,
                    currentMusic = currentMusic,
                    currentQueueEntryId = targetEntry.queueEntryId,
                    startPositionMs = startPositionMs,
                    playWhenReady = wasPlaying,
                    sourcePlaylist = sourcePlaylist,
                )
            } else {
                playerRepository.setPlaybackSession(
                    music = currentMusic,
                    queueSnapshot = finalSnapshot,
                    currentQueueEntryId = targetEntry.queueEntryId,
                    playlist = sourcePlaylist,
                )
                persistCurrentSession(
                    positionOverrideMs = startPositionMs,
                    playWhenReadyOverride = wasPlaying,
                    currentQueueEntryIdOverride = targetEntry.queueEntryId,
                )
            }
        }
    }

    private suspend fun restorePersistedSessionIfNeeded() {
        if (playerRepository.playbackQueueValue() != null || playerRepository.music.value != null) {
            return
        }
        val persisted = playbackSessionStore.load() ?: return
        val restoredPlayMode = runCatching { PlayMode.valueOf(persisted.playMode) }.getOrDefault(PlayMode.SINGLE)
        if (restoredPlayMode != playerRepository.playMode.value) {
            bridge.runSync { backend -> uniffi.ease_client_backend.ctsSavePreferencePlaymode(backend, restoredPlayMode) }
            playerRepository.reload()
        }

        val mediaController = _mediaController ?: return
        when (persisted.contextType) {
            PlaybackContextType.USER_PLAYLIST.name -> {
                val playlistId = persisted.playlistId?.let(::PlaylistId) ?: return
                val playlist = bridge.run { ctGetPlaylist(it, playlistId) } ?: return
                val snapshot = buildPlaylistSnapshot(playlist, persisted.currentQueueEntryId) ?: return
                val entry = snapshot.currentEntry() ?: snapshot.entries.firstOrNull() ?: return
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return
                playResolvedQueue(
                    player = mediaController,
                    snapshot = snapshot.copy(currentQueueEntryId = entry.queueEntryId),
                    currentMusic = music,
                    currentQueueEntryId = entry.queueEntryId,
                    startPositionMs = persisted.positionMs,
                    playWhenReady = persisted.playWhenReady,
                    sourcePlaylist = playlist,
                )
            }
            PlaybackContextType.FOLDER.name -> {
                val storageId = persisted.storageId?.let(::StorageId) ?: return
                val folderPath = persisted.folderPath ?: return
                val context = PlaybackContext(
                    type = PlaybackContextType.FOLDER,
                    storageId = storageId,
                    folderPath = folderPath,
                )
                val entries = buildList {
                    persisted.entries.forEach { persistedEntry ->
                        val musicId = MusicId(persistedEntry.musicId)
                        val musicAbstract = bridge.runSync { backend -> ctsGetMusicAbstract(backend, musicId) } ?: return@forEach
                        add(
                            PlaybackQueueEntry(
                                queueEntryId = persistedEntry.queueEntryId,
                                musicId = musicId,
                                musicAbstract = musicAbstract,
                                sourceContext = context,
                            )
                        )
                    }
                }
                if (entries.isEmpty()) {
                    playbackSessionStore.clear()
                    return
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = persisted.currentQueueEntryId,
                )
                val entry = snapshot.currentEntry() ?: entries.firstOrNull() ?: return
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return
                playResolvedQueue(
                    player = mediaController,
                    snapshot = snapshot.copy(currentQueueEntryId = entry.queueEntryId),
                    currentMusic = music,
                    currentQueueEntryId = entry.queueEntryId,
                    startPositionMs = persisted.positionMs,
                    playWhenReady = persisted.playWhenReady,
                )
            }
            PlaybackContextType.TEMPORARY.name -> {
                val context = PlaybackContext(type = PlaybackContextType.TEMPORARY)
                val entries = buildList {
                    persisted.entries.forEach { persistedEntry ->
                        val musicId = MusicId(persistedEntry.musicId)
                        val musicAbstract = bridge.runSync { backend -> ctsGetMusicAbstract(backend, musicId) } ?: return@forEach
                        add(
                            PlaybackQueueEntry(
                                queueEntryId = persistedEntry.queueEntryId,
                                musicId = musicId,
                                musicAbstract = musicAbstract,
                                sourceContext = buildPersistedSourceContext(persistedEntry),
                            )
                        )
                    }
                }
                if (entries.isEmpty()) {
                    playbackSessionStore.clear()
                    return
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = persisted.currentQueueEntryId,
                )
                val entry = snapshot.currentEntry() ?: entries.firstOrNull() ?: return
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return
                playResolvedQueue(
                    player = mediaController,
                    snapshot = snapshot.copy(currentQueueEntryId = entry.queueEntryId),
                    currentMusic = music,
                    currentQueueEntryId = entry.queueEntryId,
                    startPositionMs = persisted.positionMs,
                    playWhenReady = persisted.playWhenReady,
                )
            }
        }
    }

    private fun buildPersistedSourceContext(
        persistedEntry: PersistedPlaybackQueueEntry,
    ): PlaybackContext {
        val type = runCatching { PlaybackContextType.valueOf(persistedEntry.sourceType) }
            .getOrDefault(PlaybackContextType.TEMPORARY)
        return PlaybackContext(
            type = type,
            playlistId = persistedEntry.playlistId?.let(::PlaylistId),
            storageId = persistedEntry.storageId?.let(::StorageId),
            folderPath = persistedEntry.folderPath,
        )
    }

    private fun buildPlaylistSnapshot(
        playlist: Playlist,
        requestedQueueEntryId: String,
    ): PlaybackQueueSnapshot? {
        val context = PlaybackContext(
            type = PlaybackContextType.USER_PLAYLIST,
            playlistId = playlist.abstr.meta.id,
        )
        val entries = playlist.musics.map { music ->
            PlaybackQueueEntry(
                queueEntryId = buildPlaylistQueueEntryId(playlist.abstr.meta.id, music.meta.id),
                musicId = music.meta.id,
                musicAbstract = music,
                sourceContext = context,
            )
        }
        if (entries.isEmpty()) {
            return null
        }
        val currentQueueEntryId = entries.firstOrNull { it.queueEntryId == requestedQueueEntryId }?.queueEntryId
            ?: entries.first().queueEntryId
        return PlaybackQueueSnapshot(
            context = context,
            entries = entries,
            currentQueueEntryId = currentQueueEntryId,
        )
    }

    private fun playResolvedQueue(
        player: Player,
        snapshot: PlaybackQueueSnapshot,
        currentMusic: uniffi.ease_client_backend.Music,
        currentQueueEntryId: String,
        direction: Int = PLAY_DIRECTION_NEXT,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        sourcePlaylist: Playlist? = null,
    ) {
        playerRepository.seedPlaybackRecovery(currentQueueEntryId, direction)
        playerRepository.setPlaybackSession(
            music = currentMusic,
            queueSnapshot = snapshot,
            currentQueueEntryId = currentQueueEntryId,
            playlist = sourcePlaylist,
        )
        playQueueUtil(
            snapshot = snapshot,
            targetQueueEntryId = currentQueueEntryId,
            playMode = playerRepository.playMode.value,
            player = player,
            startPositionMs = startPositionMs,
            playWhenReady = playWhenReady,
        )
        playbackSessionStore.save(
            snapshot = snapshot,
            positionMs = startPositionMs,
            playWhenReady = playWhenReady,
            playMode = playerRepository.playMode.value,
        )
    }

    fun persistCurrentSession(
        positionOverrideMs: Long? = null,
        playWhenReadyOverride: Boolean? = null,
        currentQueueEntryIdOverride: String? = null,
    ) {
        val snapshot = playerRepository.playbackQueueValue() ?: return
        val mediaController = _mediaController
        playbackSessionStore.save(
            snapshot = snapshot.copy(
                currentQueueEntryId = currentQueueEntryIdOverride
                    ?: playerRepository.currentQueueEntryIdValue()
                    ?: snapshot.currentQueueEntryId,
            ),
            positionMs = positionOverrideMs ?: mediaController?.currentPosition ?: 0L,
            playWhenReady = playWhenReadyOverride ?: mediaController?.playWhenReady ?: playerRepository.playing.value,
            playMode = playerRepository.playMode.value,
        )
    }
}
