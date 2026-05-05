package com.kutedev.easemusicplayer.core

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.kutedev.easemusicplayer.R
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.WAKE_MODE_NETWORK
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS
import androidx.media3.common.Timeline
import androidx.media3.common.Player.COMMAND_PLAY_PAUSE
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.kutedev.easemusicplayer.MainActivity
import com.kutedev.easemusicplayer.singleton.PlaybackContext
import com.kutedev.easemusicplayer.singleton.PlaybackContextType
import com.kutedev.easemusicplayer.singleton.PlaybackCommand
import com.kutedev.easemusicplayer.singleton.PlaybackCommandBus
import com.kutedev.easemusicplayer.singleton.AssetRepository
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import com.kutedev.easemusicplayer.singleton.PlaybackQueueEntry
import com.kutedev.easemusicplayer.singleton.PlaybackQueueSnapshot
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import com.kutedev.easemusicplayer.singleton.PlaybackRuntimeKernel
import com.kutedev.easemusicplayer.singleton.ToastRepository
import com.kutedev.easemusicplayer.singleton.buildPlaylistQueueEntryId
import com.kutedev.easemusicplayer.singleton.buildFolderQueueEntryId
import com.kutedev.easemusicplayer.singleton.buildTemporaryQueueEntryId
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.ArgEnsureMusics
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ToAddMusicEntry
import uniffi.ease_client_backend.ctEnsureMusics
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctGetPlaylist
import uniffi.ease_client_backend.ctsGetMusicAbstract
import javax.inject.Inject
import com.kutedev.easemusicplayer.singleton.Bridge
import dagger.hilt.android.AndroidEntryPoint
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.StorageId
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import com.kutedev.easemusicplayer.viewmodels.playModeToastLabelResId


const val PLAYER_TO_PREV_COMMAND = "PLAYER_TO_PREV_COMMAND";
const val PLAYER_TO_NEXT_COMMAND = "PLAYER_TO_NEXT_COMMAND";

private data class PlaybackRecoveryState(
    val token: Long,
    val direction: Int,
    val attemptedQueueEntryIds: MutableSet<String>,
    var skipToastShown: Boolean = false,
)

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var toastRepository: ToastRepository
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var bridge: Bridge
    @Inject lateinit var playbackRuntimeKernel: PlaybackRuntimeKernel
    @Inject lateinit var playbackCommandBus: PlaybackCommandBus
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var _mediaSession: MediaSession? = null
    private var _prefetcher: PlaybackPrefetcher? = null
    private var recoveryState: PlaybackRecoveryState? = null

    override fun onCreate() {
        super.onCreate()
        easeLog("Playback service creating...")
        bridge.initialize()
        setMediaNotificationProvider(
            EasePlaybackNotificationProvider(
                appContext = this,
                resolvePlayMode = { playerRepository.playMode.value },
                resolvePlayModeLabel = { playMode -> getString(playModeToastLabelResId(playMode)) },
                resolveStopLabel = { getString(R.string.music_notification_stop) },
            )
        )
        val context = this

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val cache = PlaybackCache.getCache(context)
        val playerUpstreamFactory = PlaybackDataSourceFactory.create(
            appContext = context,
            bridge = bridge,
            downloadRepository = downloadRepository,
            scope = serviceScope,
            sourceTag = PLAYBACK_SOURCE_TAG_PLAYBACK,
        )
        val prefetchUpstreamFactory = PlaybackDataSourceFactory.create(
            appContext = context,
            bridge = bridge,
            downloadRepository = downloadRepository,
            scope = serviceScope,
            sourceTag = PLAYBACK_SOURCE_TAG_NEXT_PREFETCH,
        )
        val playerCacheFactory = PlaybackCache.buildCacheDataSourceFactory(context, playerUpstreamFactory)
        val prefetchCacheFactory = PlaybackCache.buildCacheDataSourceFactory(context, prefetchUpstreamFactory)
        _prefetcher = PlaybackPrefetcher(cache, prefetchCacheFactory, serviceScope)

        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(WAKE_MODE_NETWORK)
            .setLoadControl(PlaybackLoadControl.build())
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(playerCacheFactory))
            .build()
        _mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(PLAYER_CYCLE_PLAY_MODE_COMMAND, Bundle.EMPTY))
                            .add(SessionCommand(PLAYER_STOP_PLAYBACK_COMMAND, Bundle.EMPTY))
                            .apply {
                                if (!session.isMediaNotificationController(controller)) {
                                    add(SessionCommand(PLAYER_TO_PREV_COMMAND, Bundle.EMPTY))
                                    add(SessionCommand(PLAYER_TO_NEXT_COMMAND, Bundle.EMPTY))
                                }
                            }
                            .build()
                    if (session.isMediaNotificationController(controller)) {
                        // Let Media3 keep the default previous/play/next transport buttons for the
                        // notification controller, and only append overflow actions via
                        // media button preferences so we do not reintroduce duplicate actions.
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == PLAYER_TO_PREV_COMMAND) {
                        playPrevious()
                    } else if (customCommand.customAction == PLAYER_TO_NEXT_COMMAND) {
                        playNext()
                    } else if (customCommand.customAction == PLAYER_CYCLE_PLAY_MODE_COMMAND) {
                        val nextPlayMode = playerRepository.changePlayModeToNext()
                        toastRepository.emitToastRes(playModeToastLabelResId(nextPlayMode))
                        syncNotificationButtonPreferences(session)
                        refreshSystemMediaNotification()
                    } else if (customCommand.customAction == PLAYER_STOP_PLAYBACK_COMMAND) {
                        stopPlayback(session.player)
                        stopSelf()
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        syncNotificationButtonPreferences()

        fun syncCurrentMetadata(player: Player) {
            syncMetadataUtil(
                serviceScope,
                bridge,
                player,
                onUpdated = { updatedId ->
                serviceScope.launch {
                    val updatedMusic = bridge.run { backend -> ctGetMusic(backend, updatedId) } ?: return@launch
                    playerRepository.updateCurrentMusic(updatedMusic)
                }
                }
            )
            if (player is ExoPlayer) {
                refreshCurrentNotificationMetadata(player)
            }
        }

        serviceScope.launch(Dispatchers.Main.immediate) {
            playerRepository.reload()
            playerRepository.playMode.collect { playMode ->
                syncQueueForPlayMode(
                    player = player,
                    playMode = playMode,
                    preservePosition = true,
                )
                syncNotificationButtonPreferences()
                refreshSystemMediaNotification()
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerRepository.setIsPlaying(isPlaying)
                playbackRuntimeKernel.persistCurrentSession(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recoveryState = null
                    playerRepository.setIsLoading(false)
                    playbackRuntimeKernel.persistCurrentSession(player)
                } else if (playbackState == Player.STATE_READY) {
                    playerRepository.setIsLoading(false)
                    syncCurrentMetadata(player)
                    prefetchNext()
                    playlistRepository.primePlaybackMetadata(
                        currentId = playerRepository.music.value?.meta?.id,
                        nextId = playerRepository.nextMusic.value?.meta?.id,
                    )
                    playbackRuntimeKernel.persistCurrentSession(player)
                } else if (playbackState == Player.STATE_BUFFERING) {
                    playerRepository.setIsLoading(true)
                } else if (playbackState == Player.STATE_IDLE) {
                    playerRepository.setIsLoading(false)
                    _prefetcher?.cancel()
                    playbackRuntimeKernel.persistCurrentSession(player)
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncCurrentMetadata(player)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                serviceScope.launch {
                    syncRepositoryCurrentFromPlayer(player)
                    playbackRuntimeKernel.persistCurrentSession(player)
                    refreshSystemMediaNotification()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                playerRepository.notifyDurationChanged()
                playbackRuntimeKernel.persistCurrentSession(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                playerRepository.setIsLoading(false)
                _prefetcher?.cancel()
                easeError("playback error: $error")
                if (shouldRecoverFromPlaybackError(error)) {
                    serviceScope.launch {
                        recoverFromPlaybackError(player as ExoPlayer)
                    }
                } else {
                    recoveryState = null
                    player.stop()
                    player.clearMediaItems()
                    playbackRuntimeKernel.clearPlaybackState()
                    toastRepository.emitToast("播放失败")
                }
            }
        })
        serviceScope.launch(Dispatchers.Main.immediate) {
            playbackCommandBus.commands.collect { command ->
                handlePlaybackCommand(player, command)
            }
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            playbackRuntimeKernel.restorePersistedSessionIfNeeded(player)
        }
        easeLog("Playback service created")

        serviceScope.launch(Dispatchers.Main) {
            playerRepository.pauseRequest.collect {
                val player = _mediaSession?.player ?: return@collect

                if (player.isCommandAvailable(COMMAND_PLAY_PAUSE)) {
                    player.pause()
                } else {
                    easeError("media player pause failed, command COMMAND_PLAY_PAUSE is unavailable")
                }
            }
        }
    }

    private fun refreshCurrentNotificationMetadata(player: ExoPlayer) {
        val currentMediaItem = player.currentMediaItem ?: return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) {
            return
        }
        val expectedMediaId = currentMediaItem.mediaId
        val musicId = resolveMusicIdFromMediaItem(currentMediaItem) ?: return

        serviceScope.launch {
            val storedCoverKey = playerRepository.music.value
                ?.takeIf { it.meta.id == musicId }
                ?.cover
                ?: bridge.run { backend -> ctGetMusic(backend, musicId) }?.cover
            val storedCoverData = storedCoverKey?.let { key ->
                assetRepository.get(key) ?: assetRepository.load(key)
            }
            val probedMetadata = probeMusicMetadataDirectly(bridge, musicId)
            val playbackCoverData = withContext(Dispatchers.Main.immediate) {
                if (
                    player.currentMediaItem?.mediaId != expectedMediaId ||
                    player.currentMediaItemIndex != currentIndex
                ) {
                    return@withContext null
                }
                extractCurrentTracksCover(player)
            }
            val artworkData = storedCoverData ?: probedMetadata?.cover ?: playbackCoverData

            withContext(Dispatchers.Main.immediate) {
                val activeMediaItem = player.currentMediaItem ?: return@withContext
                if (
                    activeMediaItem.mediaId != expectedMediaId ||
                    player.currentMediaItemIndex != currentIndex
                ) {
                    return@withContext
                }

                val updatedMetadata = buildPlaybackNotificationMediaMetadata(
                    baseMetadata = activeMediaItem.mediaMetadata,
                    probedMetadata = probedMetadata,
                    artworkData = artworkData,
                )
                if (playbackNotificationMetadataEquals(activeMediaItem.mediaMetadata, updatedMetadata)) {
                    return@withContext
                }

                player.replaceMediaItem(
                    currentIndex,
                    activeMediaItem.buildUpon()
                        .setMediaMetadata(updatedMetadata)
                        .build(),
                )
                refreshSystemMediaNotification()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        _mediaSession?.player?.let { player ->
            stopPlayback(player)
        }
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return _mediaSession
    }

    override fun onDestroy() {
        super.onDestroy()
        _mediaSession?.player?.let { playbackRuntimeKernel.persistCurrentSession(it) }
        _mediaSession?.player?.stop()
        _mediaSession?.player?.release()
        _mediaSession?.release()
        _mediaSession = null
        _prefetcher?.cancel()
        _prefetcher = null
        recoveryState = null
        serviceScope.cancel()
    }

    private fun refreshSystemMediaNotification() {
        val session = _mediaSession ?: return
        onUpdateNotification(session, false)
    }

    private fun syncNotificationButtonPreferences(session: MediaSession? = _mediaSession) {
        val activeSession = session ?: return
        val playMode = playerRepository.playMode.value
        val buttons = buildPlaybackNotificationButtons(
            playMode = playMode,
            playModeLabel = getString(playModeToastLabelResId(playMode)),
            stopLabel = getString(R.string.music_notification_stop),
        )
        activeSession.getMediaNotificationControllerInfo()?.let { notificationController ->
            activeSession.setMediaButtonPreferences(notificationController, buttons)
        }
        activeSession.setMediaButtonPreferences(buttons)
    }

    private suspend fun handlePlaybackCommand(
        player: Player,
        command: PlaybackCommand,
    ) {
        when (command) {
            is PlaybackCommand.PlayPlaylistMusic -> {
                handlePlayPlaylistMusic(
                    player = player,
                    playlistId = command.playlistId,
                    musicId = command.musicId,
                    direction = command.direction,
                )
            }

            is PlaybackCommand.PlayFolder -> {
                handlePlayFolder(
                    player = player,
                    storageId = command.storageId,
                    folderPath = command.folderPath,
                    songs = command.songs,
                    targetEntryPath = command.targetEntryPath,
                    ensuredMusics = command.ensuredMusics,
                )
            }

            is PlaybackCommand.PlayQueueEntry -> handlePlayQueueEntry(player, command.queueEntryId)
            is PlaybackCommand.AppendEntries -> handleAppendEntries(player, command.entries)
            is PlaybackCommand.CommitQueueOrder -> handleCommitQueueOrder(player, command.orderedQueueEntryIds)
            is PlaybackCommand.RemoveQueueEntry -> handleRemoveQueueEntry(player, command.queueEntryId)
            PlaybackCommand.RemoveCurrent -> handleRemoveCurrent(player)
            is PlaybackCommand.RefreshPlaylistIfMatch -> handleRefreshPlaylistIfMatch(player, command.playlist)
            PlaybackCommand.RestorePersistedSessionIfNeeded -> playbackRuntimeKernel.restorePersistedSessionIfNeeded(player)
        }
    }

    private suspend fun handlePlayPlaylistMusic(
        player: Player,
        playlistId: PlaylistId,
        musicId: MusicId,
        direction: Int,
    ) {
        val playlist = bridge.run { ctGetPlaylist(it, playlistId) } ?: run {
            stopPlayback(player)
            return
        }
        val snapshot = playbackRuntimeKernel.buildPlaylistSnapshot(
            playlist = playlist,
            requestedQueueEntryId = buildPlaylistQueueEntryId(playlistId, musicId),
        ) ?: run {
            toastRepository.emitToast("歌曲资源不可用")
            stopPlayback(player)
            return
        }
        val target = bridge.run { ctGetMusic(it, musicId) } ?: run {
            toastRepository.emitToast("歌曲资源不可用")
            stopPlayback(player)
            return
        }
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = snapshot,
            currentMusic = target,
            currentQueueEntryId = snapshot.currentQueueEntryId,
            direction = direction,
            sourcePlaylist = playlist,
        )
    }

    private suspend fun handlePlayFolder(
        player: Player,
        storageId: StorageId,
        folderPath: String,
        songs: List<StorageEntry>,
        targetEntryPath: String,
        ensuredMusics: List<AddedMusic>? = null,
    ) {
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
            return
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
            return
        }
        val targetEntry = entries.firstOrNull { it.musicId == targetMusicId } ?: entries.first()
        val target = bridge.run { ctGetMusic(it, targetEntry.musicId) } ?: run {
            toastRepository.emitToast("歌曲资源不可用")
            stopPlayback(player)
            return
        }
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = PlaybackQueueSnapshot(
                context = context,
                entries = entries,
                currentQueueEntryId = targetEntry.queueEntryId,
            ),
            currentMusic = target,
            currentQueueEntryId = targetEntry.queueEntryId,
        )
    }

    private suspend fun handlePlayQueueEntry(
        player: Player,
        queueEntryId: String,
    ) {
        val queue = playerRepository.playbackQueueValue() ?: return
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
        val entry = queue.entries.getOrNull(targetIndex) ?: return
        playQueueEntry(
            player = player,
            entry = entry,
            queue = queue,
            direction = direction,
        )
    }

    private suspend fun handleAppendEntries(
        player: Player,
        entries: List<StorageEntry>,
    ) {
        val songs = entries
            .filter { entry -> entry.entryTyp() == StorageEntryType.MUSIC }
            .distinctBy { entry -> "${entry.storageId.value}:${entry.path}" }
        if (songs.isEmpty()) {
            toastRepository.emitToast("当前没有可加入播放队列的音乐")
            return
        }

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
            return
        }
        playlistRepository.requestTotalDuration(ensured)
        val appendedEntries = buildTemporaryEntries(songs = songs, ensured = ensured)
        if (appendedEntries.isEmpty()) {
            toastRepository.emitToast("当前没有可加入播放队列的音乐")
            return
        }

        val activeQueue = playerRepository.playbackQueueValue()
        val activeMusic = playerRepository.music.value
        if (activeQueue == null || activeMusic == null) {
            val targetEntry = appendedEntries.first()
            val targetMusic = bridge.run { ctGetMusic(it, targetEntry.musicId) } ?: run {
                toastRepository.emitToast("歌曲资源不可用")
                return
            }
            val snapshot = PlaybackQueueSnapshot(
                context = PlaybackContext(type = PlaybackContextType.TEMPORARY),
                entries = appendedEntries,
                currentQueueEntryId = targetEntry.queueEntryId,
            )
            playbackRuntimeKernel.playResolvedQueue(
                player = player,
                snapshot = snapshot,
                currentMusic = targetMusic,
                currentQueueEntryId = targetEntry.queueEntryId,
                playWhenReady = false,
            )
            toastRepository.emitToast("已加入播放队列")
            return
        }

        val currentQueueEntryId = playerRepository.currentQueueEntryIdValue() ?: activeQueue.currentQueueEntryId
        val combinedSnapshot = PlaybackQueueSnapshot(
            context = PlaybackContext(type = PlaybackContextType.TEMPORARY),
            entries = activeQueue.entries + appendedEntries,
            currentQueueEntryId = currentQueueEntryId,
        )
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = combinedSnapshot,
            currentMusic = activeMusic,
            currentQueueEntryId = currentQueueEntryId,
            startPositionMs = player.currentPosition,
            playWhenReady = player.playWhenReady,
        )
        toastRepository.emitToast("已加入播放队列")
    }

    private suspend fun handleCommitQueueOrder(
        player: Player,
        orderedQueueEntryIds: List<String>,
    ) {
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        if (
            orderedQueueEntryIds.size != queue.entries.size ||
            orderedQueueEntryIds == queue.entries.map { it.queueEntryId } ||
            orderedQueueEntryIds.distinct().size != orderedQueueEntryIds.size
        ) {
            return
        }
        val entriesById = queue.entries.associateBy { it.queueEntryId }
        if (orderedQueueEntryIds.any { it !in entriesById }) {
            return
        }
        val nextSnapshot = queue.copy(
            entries = orderedQueueEntryIds.map { entriesById.getValue(it) },
            currentQueueEntryId = currentEntryId,
        )
        if (tryApplyQueueEditInPlace(player, nextSnapshot, currentEntryId)) {
            return
        }
        applyEditedQueueSnapshot(
            player = player,
            snapshot = nextSnapshot,
            requestedQueueEntryId = currentEntryId,
            sourcePlaylist = currentSourcePlaylistFor(nextSnapshot),
        )
    }

    private suspend fun handleRemoveQueueEntry(
        player: Player,
        queueEntryId: String,
    ) {
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        if (queueEntryId == currentEntryId) {
            handleRemoveCurrent(player)
            return
        }
        removeNonCurrentQueueEntry(player, queue, queueEntryId)
    }

    private suspend fun handleRefreshPlaylistIfMatch(
        player: Player,
        playlist: Playlist,
    ) {
        val activeQueue = playerRepository.playbackQueueValue() ?: return
        if (activeQueue.context.type != PlaybackContextType.USER_PLAYLIST || activeQueue.context.playlistId != playlist.abstr.meta.id) {
            return
        }
        playerRepository.setCurrentSourcePlaylist(playlist)
        playbackRuntimeKernel.persistCurrentSession(player)
    }

    private suspend fun handleRemoveCurrent(
        player: Player,
    ) {
        val queue = playerRepository.playbackQueueValue() ?: return
        removeCurrentFromQueue(player, queue)
    }

    private suspend fun removeNonCurrentQueueEntry(
        player: Player,
        queue: PlaybackQueueSnapshot,
        queueEntryId: String,
    ) {
        val nextEntries = queue.entries.filterNot { it.queueEntryId == queueEntryId }
        if (nextEntries.isEmpty()) {
            stopPlayback(player)
            return
        }
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: queue.currentQueueEntryId
        val nextSnapshot = queue.copy(
            entries = nextEntries,
            currentQueueEntryId = currentEntryId,
        )
        if (tryApplyQueueEditInPlace(player, nextSnapshot, currentEntryId)) {
            return
        }
        applyEditedQueueSnapshot(
            player = player,
            snapshot = nextSnapshot,
            requestedQueueEntryId = currentEntryId,
            sourcePlaylist = currentSourcePlaylistFor(nextSnapshot),
        )
    }

    private suspend fun removeCurrentFromQueue(
        player: Player,
        queue: PlaybackQueueSnapshot,
    ) {
        val currentEntryId = playerRepository.currentQueueEntryIdValue() ?: return
        val currentIndex = queue.indexOf(currentEntryId)
        if (currentIndex < 0) {
            return
        }
        val nextEntries = queue.entries.filterNot { it.queueEntryId == currentEntryId }
        if (nextEntries.isEmpty()) {
            stopPlayback(player)
            return
        }
        val nextIndex = currentIndex.coerceAtMost(nextEntries.lastIndex)
        val nextEntry = nextEntries[nextIndex]
        val nextSnapshot = queue.copy(entries = nextEntries, currentQueueEntryId = nextEntry.queueEntryId)
        val nextMusic = bridge.run { ctGetMusic(it, nextEntry.musicId) } ?: run {
            stopPlayback(player)
            return
        }
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = nextSnapshot,
            currentMusic = nextMusic,
            currentQueueEntryId = nextEntry.queueEntryId,
            playWhenReady = player.playWhenReady,
            sourcePlaylist = currentSourcePlaylistFor(nextSnapshot),
        )
    }

    private suspend fun playQueueEntry(
        player: Player,
        entry: PlaybackQueueEntry,
        queue: PlaybackQueueSnapshot,
        direction: Int,
    ) {
        val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = queue.copy(currentQueueEntryId = entry.queueEntryId),
            currentMusic = music,
            currentQueueEntryId = entry.queueEntryId,
            direction = direction,
            sourcePlaylist = if (queue.context.type == PlaybackContextType.USER_PLAYLIST) playerRepository.playlist.value else null,
        )
    }

    private fun currentSourcePlaylistFor(snapshot: PlaybackQueueSnapshot): Playlist? {
        return if (snapshot.context.type == PlaybackContextType.USER_PLAYLIST) {
            playerRepository.playlist.value
        } else {
            null
        }
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

    private fun tryApplyQueueEditInPlace(
        player: Player,
        snapshot: PlaybackQueueSnapshot,
        requestedQueueEntryId: String,
    ): Boolean {
        val plan = buildPlaybackQueuePlan(
            snapshot = snapshot,
            targetQueueEntryId = requestedQueueEntryId,
            playMode = playerRepository.playMode.value,
        ) ?: return false
        val desiredIds = plan.mediaItems.map { it.mediaId }
        if (desiredIds.isEmpty()) {
            return false
        }
        val currentIds = (0 until player.mediaItemCount)
            .map { index -> player.getMediaItemAt(index).mediaId }
            .toMutableList()
        if (!desiredIds.all { desiredId -> currentIds.contains(desiredId) }) {
            return false
        }

        player.repeatMode = plan.repeatMode
        if (currentIds != desiredIds) {
            if (!player.isCommandAvailable(COMMAND_CHANGE_MEDIA_ITEMS)) {
                return false
            }
            val desiredIdSet = desiredIds.toSet()
            for (index in currentIds.lastIndex downTo 0) {
                if (currentIds[index] !in desiredIdSet) {
                    player.removeMediaItem(index)
                    currentIds.removeAt(index)
                }
            }
            desiredIds.forEachIndexed { targetIndex, desiredId ->
                val currentIndex = currentIds.indexOf(desiredId)
                if (currentIndex == -1) {
                    return false
                }
                if (currentIndex != targetIndex) {
                    player.moveMediaItem(currentIndex, targetIndex)
                    val moved = currentIds.removeAt(currentIndex)
                    currentIds.add(targetIndex, moved)
                }
            }
        }

        playerRepository.updatePlaybackQueue(
            queueSnapshot = snapshot,
            currentQueueEntryId = requestedQueueEntryId,
            playlist = currentSourcePlaylistFor(snapshot),
        )
        playbackRuntimeKernel.persistCurrentSession(
            player,
            currentQueueEntryIdOverride = requestedQueueEntryId,
        )
        return player.currentMediaItem?.mediaId == requestedQueueEntryId
    }

    private suspend fun applyEditedQueueSnapshot(
        player: Player,
        snapshot: PlaybackQueueSnapshot,
        requestedQueueEntryId: String,
        sourcePlaylist: Playlist? = if (snapshot.context.type == PlaybackContextType.USER_PLAYLIST) playerRepository.playlist.value else null,
    ) {
        if (snapshot.entries.isEmpty()) {
            stopPlayback(player)
            return
        }
        val targetEntry = snapshot.entries.firstOrNull { it.queueEntryId == requestedQueueEntryId }
            ?: snapshot.currentEntry()
            ?: snapshot.entries.firstOrNull()
            ?: run {
                stopPlayback(player)
                return
            }
        val currentMusic = bridge.run { ctGetMusic(it, targetEntry.musicId) } ?: run {
            stopPlayback(player)
            return
        }
        val preservePosition = currentMusic.meta.id == playerRepository.music.value?.meta?.id
        val startPositionMs = if (preservePosition) player.currentPosition else 0L
        val finalSnapshot = snapshot.copy(currentQueueEntryId = targetEntry.queueEntryId)
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = finalSnapshot,
            currentMusic = currentMusic,
            currentQueueEntryId = targetEntry.queueEntryId,
            startPositionMs = startPositionMs,
            playWhenReady = player.playWhenReady,
            sourcePlaylist = sourcePlaylist,
        )
    }

    private fun stopPlayback(player: Player) {
        player.stop()
        player.clearMediaItems()
        playerRepository.setIsLoading(false)
        playbackRuntimeKernel.clearPlaybackState()
    }

    private fun playNext() {
        val player = _mediaSession?.player ?: return
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (currentIndex < 0 || queue.entries.isEmpty()) {
            return
        }
        if (currentIndex == queue.entries.lastIndex && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
            return
        }
        if (playbackRuntimeKernel.seekAdjacentMediaItem(player, PLAY_DIRECTION_NEXT)) {
            return
        }
        val nextIndex = (currentIndex + 1) % queue.entries.size
        val entry = queue.entries.getOrNull(nextIndex) ?: return
        serviceScope.launch {
            playQueueEntry(
                player = player,
                entry = entry,
                queue = queue,
                direction = PLAY_DIRECTION_NEXT,
            )
        }
    }

    private fun playPrevious() {
        val player = _mediaSession?.player ?: return
        val queue = playerRepository.playbackQueueValue() ?: return
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (currentIndex < 0 || queue.entries.isEmpty()) {
            return
        }
        if (currentIndex == 0 && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
            return
        }
        if (playbackRuntimeKernel.seekAdjacentMediaItem(player, PLAY_DIRECTION_PREVIOUS)) {
            return
        }
        val previousIndex = (currentIndex + queue.entries.size - 1) % queue.entries.size
        val entry = queue.entries.getOrNull(previousIndex) ?: return
        serviceScope.launch {
            playQueueEntry(
                player = player,
                entry = entry,
                queue = queue,
                direction = PLAY_DIRECTION_PREVIOUS,
            )
        }
    }

    private fun prefetchNext() {
        val next = playerRepository.nextMusic.value
        if (next == null) {
            _prefetcher?.cancel()
            return
        }
        val uri = buildPlaybackMusicUri(next.meta.id)
        _prefetcher?.prefetch(uri, PlaybackCachePolicy.prefetchBytesForSeconds())
    }

    private fun shouldRecoverFromPlaybackError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return true
        }
        return error.findCause<FileNotFoundException>() != null
    }

    private suspend fun recoverFromPlaybackError(player: ExoPlayer) {
        val snapshot = playerRepository.playbackQueueValue()
        val current = playerRepository.music.value
        val seed = playerRepository.recoverySeed.value
        if (snapshot == null || current == null || seed == null) {
            stopPlayback(player)
            toastRepository.emitToast("播放失败")
            recoveryState = null
            return
        }

        val active = if (recoveryState?.token != seed.token) {
            PlaybackRecoveryState(
                token = seed.token,
                direction = seed.direction,
                attemptedQueueEntryIds = mutableSetOf(seed.queueEntryId),
            ).also { recoveryState = it }
        } else {
            recoveryState!!.also { it.attemptedQueueEntryIds.add(seed.queueEntryId) }
        }

        val currentQueueEntryId = playerRepository.currentQueueEntryIdValue() ?: seed.queueEntryId
        var candidateEntry = findRecoveryCandidate(snapshot, currentQueueEntryId, active.direction, active.attemptedQueueEntryIds)
        var candidate = candidateEntry?.let { entry ->
            bridge.run { ctGetMusic(it, entry.musicId) }
        }
        while (candidateEntry != null && candidate == null) {
            active.attemptedQueueEntryIds.add(candidateEntry.queueEntryId)
            candidateEntry = findRecoveryCandidate(snapshot, candidateEntry.queueEntryId, active.direction, active.attemptedQueueEntryIds)
            candidate = candidateEntry?.let { entry ->
                bridge.run { ctGetMusic(it, entry.musicId) }
            }
        }
        if (candidateEntry == null || candidate == null) {
            recoveryState = null
            toastRepository.emitToast("歌曲资源不可用")
            stopPlayback(player)
            return
        }

        if (!active.skipToastShown) {
            toastRepository.emitToast("歌曲资源不可用，已跳过")
            active.skipToastShown = true
        }
        active.attemptedQueueEntryIds.add(candidateEntry.queueEntryId)
        playbackRuntimeKernel.playResolvedQueue(
            player = player,
            snapshot = snapshot.copy(currentQueueEntryId = candidateEntry.queueEntryId),
            currentMusic = candidate,
            currentQueueEntryId = candidateEntry.queueEntryId,
            direction = active.direction,
            sourcePlaylist = if (snapshot.context.type == PlaybackContextType.USER_PLAYLIST) playerRepository.playlist.value else null,
        )
    }

    private suspend fun syncRepositoryCurrentFromPlayer(player: Player) {
        val snapshot = playerRepository.playbackQueueValue() ?: return
        val queueEntryId = player.currentMediaItem?.mediaId ?: return
        val entry = snapshot.entries.firstOrNull { it.queueEntryId == queueEntryId } ?: return
        val direction = when {
            playerRepository.nextMusic.value?.meta?.id == entry.musicId -> PLAY_DIRECTION_NEXT
            playerRepository.previousMusic.value?.meta?.id == entry.musicId -> PLAY_DIRECTION_PREVIOUS
            else -> PLAY_DIRECTION_NEXT
        }
        playerRepository.seedPlaybackRecovery(queueEntryId, direction)
        if (playerRepository.currentQueueEntryIdValue() == queueEntryId && playerRepository.music.value?.meta?.id == entry.musicId) {
            return
        }
        val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return
        playerRepository.updateCurrentQueueEntry(queueEntryId, music)
    }

    private fun syncQueueForPlayMode(
        player: Player,
        playMode: PlayMode,
        preservePosition: Boolean,
    ) {
        player.repeatMode = repeatModeFor(playMode)
        val snapshot = playerRepository.playbackQueueValue() ?: return
        val currentQueueEntryId = playerRepository.currentQueueEntryIdValue() ?: return
        val plan = buildPlaybackQueuePlan(
            snapshot = snapshot,
            targetQueueEntryId = currentQueueEntryId,
            playMode = playMode,
        ) ?: return

        val desiredIds = plan.mediaItems.map { it.mediaId }
        val currentIds = (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).mediaId }
        if (desiredIds == currentIds && player.currentMediaItemIndex == plan.startIndex) {
            return
        }
        val currentPosition = if (preservePosition) player.currentPosition else 0L
        val shouldResume = player.playWhenReady
        player.stop()
        player.clearMediaItems()
        player.setMediaItems(plan.mediaItems, plan.startIndex, currentPosition.coerceAtLeast(0L))
        player.prepare()
        if (shouldResume) {
            player.play()
        } else {
            player.pause()
        }
        playbackRuntimeKernel.persistCurrentSession(player)
    }

    private fun findRecoveryCandidate(
        snapshot: PlaybackQueueSnapshot,
        currentQueueEntryId: String,
        direction: Int,
        attemptedQueueEntryIds: Set<String>
    ): PlaybackQueueEntry? {
        val entries = snapshot.entries
        if (entries.isEmpty()) {
            return null
        }
        val startIndex = entries.indexOfFirst { it.queueEntryId == currentQueueEntryId }
        if (startIndex == -1) {
            return null
        }
        val shouldWrap = playerRepository.playMode.value == PlayMode.LIST_LOOP
        var index = startIndex
        var steps = 0
        while (steps < entries.size - 1) {
            index = if (direction >= 0) {
                if (index == entries.lastIndex) {
                    if (!shouldWrap) {
                        return null
                    }
                    0
                } else {
                    index + 1
                }
            } else {
                if (index == 0) {
                    if (!shouldWrap) {
                        return null
                    }
                    entries.lastIndex
                } else {
                    index - 1
                }
            }
            val candidate = entries[index]
            if (!attemptedQueueEntryIds.contains(candidate.queueEntryId)) {
                return candidate
            }
            steps += 1
        }
        return null
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var cursor: Throwable? = this
        while (cursor != null) {
            if (cursor is T) {
                return cursor
            }
            cursor = cursor.cause
        }
        return null
    }
}
