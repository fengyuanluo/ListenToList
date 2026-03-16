package com.kutedev.easemusicplayer.core

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.WAKE_MODE_NETWORK
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Player.COMMAND_PLAY_PAUSE
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.kutedev.easemusicplayer.MainActivity
import com.kutedev.easemusicplayer.singleton.PlaybackContext
import com.kutedev.easemusicplayer.singleton.PlaybackContextType
import com.kutedev.easemusicplayer.singleton.PlaybackQueueEntry
import com.kutedev.easemusicplayer.singleton.PlaybackQueueSnapshot
import com.kutedev.easemusicplayer.singleton.PlaybackSessionStore
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import com.kutedev.easemusicplayer.singleton.buildPlaylistQueueEntryId
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.ctGetMusic
import javax.inject.Inject
import com.kutedev.easemusicplayer.singleton.Bridge
import dagger.hilt.android.AndroidEntryPoint
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.PlayMode


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
    @Inject lateinit var bridge: Bridge
    @Inject lateinit var playbackSessionStore: PlaybackSessionStore
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var _mediaSession: MediaSession? = null
    private var _prefetcher: PlaybackPrefetcher? = null
    private var recoveryState: PlaybackRecoveryState? = null

    override fun onCreate() {
        super.onCreate()
        easeLog("Playback service creating...")
        val context = this

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val cache = PlaybackCache.getCache(context)
        val playerUpstreamFactory = PlaybackDataSourceFactory.create(
            bridge = bridge,
            scope = serviceScope,
            sourceTag = PLAYBACK_SOURCE_TAG_PLAYBACK,
        )
        val prefetchUpstreamFactory = PlaybackDataSourceFactory.create(
            bridge = bridge,
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
                    if (session.isMediaNotificationController(controller)) {
                        val customPrevCommand = SessionCommand(PLAYER_TO_PREV_COMMAND, Bundle.EMPTY)
                        val customNextCommand = SessionCommand(PLAYER_TO_NEXT_COMMAND, Bundle.EMPTY)

                        val sessionCommands =
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(customPrevCommand)
                                .add(customNextCommand)
                                .build()
                        val playerCommands =
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                                .remove(Player.COMMAND_SEEK_TO_NEXT)
                                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                                .remove(Player.COMMAND_SEEK_BACK)
                                .remove(Player.COMMAND_SEEK_FORWARD)
                                .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                                .build()
                        // Custom layout and available commands to configure the legacy/framework session.
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setCustomLayout(
                                ImmutableList.of(
                                    CommandButton.Builder()
                                        .setSessionCommand(customPrevCommand)
                                        .setIconResId(CommandButton.getIconResIdForIconConstant(CommandButton.ICON_PREVIOUS))
                                        .setDisplayName("Previous")
                                        .build(),
                                    CommandButton.Builder()
                                        .setSessionCommand(customNextCommand)
                                        .setIconResId(CommandButton.getIconResIdForIconConstant(CommandButton.ICON_NEXT))
                                        .setDisplayName("Next")
                                        .build(),
                                )
                            )
                            .setAvailablePlayerCommands(playerCommands)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
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
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()

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
        }

        serviceScope.launch(Dispatchers.Main.immediate) {
            playerRepository.playMode.collect { playMode ->
                syncQueueForPlayMode(
                    player = player,
                    playMode = playMode,
                    preservePosition = true,
                )
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerRepository.setIsPlaying(isPlaying)
                persistCurrentSession(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recoveryState = null
                    playerRepository.setIsLoading(false)
                    persistCurrentSession(player)
                } else if (playbackState == Player.STATE_READY) {
                    playerRepository.setIsLoading(false)
                    syncCurrentMetadata(player)
                    prefetchNext()
                    playlistRepository.primePlaybackMetadata(
                        currentId = playerRepository.music.value?.meta?.id,
                        nextId = playerRepository.nextMusic.value?.meta?.id,
                    )
                    persistCurrentSession(player)
                } else if (playbackState == Player.STATE_BUFFERING) {
                    playerRepository.setIsLoading(true)
                } else if (playbackState == Player.STATE_IDLE) {
                    playerRepository.setIsLoading(false)
                    _prefetcher?.cancel()
                    persistCurrentSession(player)
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncCurrentMetadata(player)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                serviceScope.launch {
                    syncRepositoryCurrentFromPlayer(player)
                    persistCurrentSession(player)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                playerRepository.notifyDurationChanged()
                persistCurrentSession(player)
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
                    playerRepository.resetCurrent()
                    playbackSessionStore.clear()
                    toastRepository.emitToast("播放失败")
                }
            }
        })
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return _mediaSession
    }

    override fun onDestroy() {
        super.onDestroy()
        _mediaSession?.player?.let { persistCurrentSession(it) }
        _mediaSession?.player?.stop()
        _mediaSession?.player?.release()
        _mediaSession?.release()
        _mediaSession = null
        _prefetcher?.cancel()
        _prefetcher = null
        recoveryState = null
        serviceScope.cancel()
    }

    fun play(
        musicAbstract: MusicAbstract,
        playlist: Playlist,
        direction: Int = PLAY_DIRECTION_NEXT
    ) {
        val player = _mediaSession?.player ?: return

        serviceScope.launch {
            val targetAbstract = playlist.musics.find { it.meta.id == musicAbstract.meta.id }
            if (targetAbstract == null) {
                player.stop()
                player.clearMediaItems()
                playerRepository.resetCurrent()
                playerRepository.setIsLoading(false)
                playbackSessionStore.clear()
                toastRepository.emitToast("歌曲资源不可用")
                easeError("target music abstract missing in playlist=${playlist.abstr.meta.id.value}, id=${musicAbstract.meta.id.value}")
                return@launch
            }
            val target = bridge.run { ctGetMusic(it, targetAbstract.meta.id) }
            if (target == null) {
                player.stop()
                player.clearMediaItems()
                playerRepository.resetCurrent()
                playerRepository.setIsLoading(false)
                playbackSessionStore.clear()
                toastRepository.emitToast("歌曲资源不可用")
                easeError("target music missing in playlist=${playlist.abstr.meta.id.value}, id=${musicAbstract.meta.id.value}")
                return@launch
            }
            val queueEntryId = buildPlaylistQueueEntryId(playlist.abstr.meta.id, target.meta.id)
            val snapshot = buildPlaylistSnapshot(playlist, queueEntryId) ?: run {
                player.stop()
                player.clearMediaItems()
                playerRepository.resetCurrent()
                playbackSessionStore.clear()
                return@launch
            }
            playResolvedQueue(
                player = player,
                snapshot = snapshot,
                currentMusic = target,
                currentQueueEntryId = snapshot.currentQueueEntryId,
                direction = direction,
                sourcePlaylist = playlist,
            )
        }
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
        if (seekAdjacent(player, PLAY_DIRECTION_NEXT)) {
            return
        }
        val nextIndex = (currentIndex + 1) % queue.entries.size
        val entry = queue.entries.getOrNull(nextIndex) ?: return
        serviceScope.launch {
            val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return@launch
            playResolvedQueue(
                player = player,
                snapshot = queue.copy(currentQueueEntryId = entry.queueEntryId),
                currentMusic = music,
                currentQueueEntryId = entry.queueEntryId,
                direction = PLAY_DIRECTION_NEXT,
                sourcePlaylist = if (queue.context.type == PlaybackContextType.USER_PLAYLIST) playerRepository.playlist.value else null,
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
        if (seekAdjacent(player, PLAY_DIRECTION_PREVIOUS)) {
            return
        }
        val previousIndex = (currentIndex + queue.entries.size - 1) % queue.entries.size
        val entry = queue.entries.getOrNull(previousIndex) ?: return
        serviceScope.launch {
            val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return@launch
            playResolvedQueue(
                player = player,
                snapshot = queue.copy(currentQueueEntryId = entry.queueEntryId),
                currentMusic = music,
                currentQueueEntryId = entry.queueEntryId,
                direction = PLAY_DIRECTION_PREVIOUS,
                sourcePlaylist = if (queue.context.type == PlaybackContextType.USER_PLAYLIST) playerRepository.playlist.value else null,
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
            player.stop()
            player.clearMediaItems()
            playerRepository.resetCurrent()
            playbackSessionStore.clear()
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
        val candidateEntry = findRecoveryCandidate(snapshot, currentQueueEntryId, active.direction, active.attemptedQueueEntryIds)
        if (candidateEntry == null) {
            recoveryState = null
            toastRepository.emitToast("歌曲资源不可用")
            player.stop()
            player.clearMediaItems()
            playerRepository.resetCurrent()
            playbackSessionStore.clear()
            return
        }
        val candidate = bridge.run { ctGetMusic(it, candidateEntry.musicId) }
        if (candidate == null) {
            active.attemptedQueueEntryIds.add(candidateEntry.queueEntryId)
            recoverFromPlaybackError(player)
            return
        }

        if (!active.skipToastShown) {
            toastRepository.emitToast("歌曲资源不可用，已跳过")
            active.skipToastShown = true
        }
        active.attemptedQueueEntryIds.add(candidateEntry.queueEntryId)
        playResolvedQueue(
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

    private fun seekAdjacent(player: Player, direction: Int): Boolean {
        val snapshot = playerRepository.playbackQueueValue() ?: return false
        val currentIndex = playerRepository.currentQueueIndexValue()
        if (currentIndex < 0 || snapshot.entries.isEmpty()) {
            return false
        }
        val targetIndex = if (direction >= 0) {
            if (currentIndex == snapshot.entries.lastIndex && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
                return false
            }
            (currentIndex + 1) % snapshot.entries.size
        } else {
            if (currentIndex == 0 && playerRepository.playMode.value != PlayMode.LIST_LOOP) {
                return false
            }
            (currentIndex + snapshot.entries.size - 1) % snapshot.entries.size
        }
        val target = snapshot.entries.getOrNull(targetIndex) ?: return false

        val command = if (direction >= 0) {
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        } else {
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        }
        if (!player.isCommandAvailable(command)) {
            return false
        }
        playerRepository.seedPlaybackRecovery(target.queueEntryId, direction)
        if (direction >= 0) {
            player.seekToNextMediaItem()
        } else {
            player.seekToPreviousMediaItem()
        }
        player.play()
        persistCurrentSession(player, currentQueueEntryIdOverride = target.queueEntryId)
        return true
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
        persistCurrentSession(player)
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
        persistCurrentSession(player)
    }

    private fun persistCurrentSession(
        player: Player,
        currentQueueEntryIdOverride: String? = null,
    ) {
        val snapshot = playerRepository.playbackQueueValue() ?: return
        playbackSessionStore.save(
            snapshot = snapshot.copy(
                currentQueueEntryId = currentQueueEntryIdOverride
                    ?: playerRepository.currentQueueEntryIdValue()
                    ?: snapshot.currentQueueEntryId,
            ),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            playMode = playerRepository.playMode.value,
        )
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
