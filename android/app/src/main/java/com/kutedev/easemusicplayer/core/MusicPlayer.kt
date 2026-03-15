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
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
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
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.PlayMode


const val PLAYER_TO_PREV_COMMAND = "PLAYER_TO_PREV_COMMAND";
const val PLAYER_TO_NEXT_COMMAND = "PLAYER_TO_NEXT_COMMAND";

private data class PlaybackRecoveryState(
    val token: Long,
    val playlistId: PlaylistId,
    val direction: Int,
    val attemptedIds: MutableSet<MusicId>,
    var skipToastShown: Boolean = false,
)

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var toastRepository: ToastRepository
    @Inject lateinit var bridge: Bridge
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

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerRepository.setIsPlaying(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recoveryState = null
                    playOnComplete()
                } else if (playbackState == Player.STATE_READY) {
                    playerRepository.setIsLoading(false)
                    syncCurrentMetadata(player)
                    prefetchNext()
                    playlistRepository.primePlaybackMetadata(
                        currentId = playerRepository.music.value?.meta?.id,
                        nextId = playerRepository.nextMusic.value?.meta?.id,
                    )
                } else if (playbackState == Player.STATE_BUFFERING) {
                    playerRepository.setIsLoading(true)
                } else if (playbackState == Player.STATE_IDLE) {
                    playerRepository.setIsLoading(false)
                    _prefetcher?.cancel()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncCurrentMetadata(player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                playerRepository.notifyDurationChanged()
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
                playerRepository.resetCurrent()
                playerRepository.setIsLoading(false)
                toastRepository.emitToast("歌曲资源不可用")
                easeError("target music abstract missing in playlist=${playlist.abstr.meta.id.value}, id=${musicAbstract.meta.id.value}")
                return@launch
            }
            val target = bridge.run { ctGetMusic(it, targetAbstract.meta.id) }
            if (target == null) {
                playerRepository.resetCurrent()
                playerRepository.setIsLoading(false)
                toastRepository.emitToast("歌曲资源不可用")
                easeError("target music missing in playlist=${playlist.abstr.meta.id.value}, id=${musicAbstract.meta.id.value}")
                return@launch
            }
            playerRepository.seedPlaybackRecovery(playlist.abstr.meta.id, target.meta.id, direction)
            playerRepository.setCurrent(target, playlist)
            playUtil(BuildMediaContext(bridge = bridge, scope = serviceScope), target, player as ExoPlayer)
        }
    }

    private fun playOnComplete() {
        val m = playerRepository.onCompleteMusic.value
        val p = playerRepository.playlist.value
        if (m != null && p != null) {
            play(m, p, PLAY_DIRECTION_NEXT)
        }
    }

    private fun playNext() {
        val m = playerRepository.nextMusic.value
        val p = playerRepository.playlist.value
        if (m != null && p != null) {
            play(m, p, PLAY_DIRECTION_NEXT)
        }
    }

    private fun playPrevious() {
        val m = playerRepository.previousMusic.value
        val p = playerRepository.playlist.value
        if (m != null && p != null) {
            play(m, p, PLAY_DIRECTION_PREVIOUS)
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
        val playlist = playerRepository.playlist.value
        val current = playerRepository.music.value
        val seed = playerRepository.recoverySeed.value
        if (playlist == null || current == null || seed == null) {
            player.stop()
            player.clearMediaItems()
            playerRepository.resetCurrent()
            toastRepository.emitToast("播放失败")
            recoveryState = null
            return
        }

        val active = if (
            recoveryState?.token != seed.token ||
            recoveryState?.playlistId != playlist.abstr.meta.id
        ) {
            PlaybackRecoveryState(
                token = seed.token,
                playlistId = playlist.abstr.meta.id,
                direction = seed.direction,
                attemptedIds = mutableSetOf(seed.musicId, current.meta.id),
            ).also { recoveryState = it }
        } else {
            recoveryState!!.also { it.attemptedIds.add(current.meta.id) }
        }

        val candidateAbstract = findRecoveryCandidate(playlist, current.meta.id, active.direction, active.attemptedIds)
        if (candidateAbstract == null) {
            recoveryState = null
            toastRepository.emitToast("歌曲资源不可用")
            player.stop()
            player.clearMediaItems()
            playerRepository.resetCurrent()
            return
        }
        val candidate = bridge.run { ctGetMusic(it, candidateAbstract.meta.id) }
        if (candidate == null) {
            active.attemptedIds.add(candidateAbstract.meta.id)
            recoverFromPlaybackError(player)
            return
        }

        if (!active.skipToastShown) {
            toastRepository.emitToast("歌曲资源不可用，已跳过")
            active.skipToastShown = true
        }
        active.attemptedIds.add(candidate.meta.id)
        playerRepository.setCurrent(candidate, playlist)
        playUtil(BuildMediaContext(bridge = bridge, scope = serviceScope), candidate, player)
    }

    private fun findRecoveryCandidate(
        playlist: Playlist,
        currentId: MusicId,
        direction: Int,
        attemptedIds: Set<MusicId>
    ): MusicAbstract? {
        val musics = playlist.musics
        if (musics.isEmpty()) {
            return null
        }
        val startIndex = musics.indexOfFirst { it.meta.id == currentId }
        if (startIndex == -1) {
            return null
        }
        val shouldWrap = playerRepository.playMode.value == PlayMode.LIST_LOOP
        var index = startIndex
        var steps = 0
        while (steps < musics.size - 1) {
            index = if (direction >= 0) {
                if (index == musics.lastIndex) {
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
                    musics.lastIndex
                } else {
                    index - 1
                }
            }
            val candidate = musics[index]
            if (!attemptedIds.contains(candidate.meta.id)) {
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
