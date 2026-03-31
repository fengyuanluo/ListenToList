package com.kutedev.easemusicplayer.singleton

import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import com.kutedev.easemusicplayer.core.PLAY_DIRECTION_NEXT
import com.kutedev.easemusicplayer.core.playQueueUtil
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctGetPlaylist
import uniffi.ease_client_backend.ctsGetMusicAbstract
import uniffi.ease_client_backend.ctsSavePreferencePlaymode
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackRuntimeKernel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val playbackSessionStore: PlaybackSessionStore,
    private val bridge: Bridge,
) {
    fun buildPlaylistSnapshot(
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

    fun playResolvedQueue(
        player: Player,
        snapshot: PlaybackQueueSnapshot,
        currentMusic: Music,
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
        persistCurrentSession(
            positionMs = startPositionMs,
            playWhenReady = playWhenReady,
            currentQueueEntryIdOverride = currentQueueEntryId,
        )
    }

    fun persistCurrentSession(
        player: Player,
        currentQueueEntryIdOverride: String? = null,
    ) {
        persistCurrentSession(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            currentQueueEntryIdOverride = currentQueueEntryIdOverride,
        )
    }

    fun persistCurrentSession(
        positionMs: Long,
        playWhenReady: Boolean,
        currentQueueEntryIdOverride: String? = null,
    ) {
        val snapshot = playerRepository.playbackQueueValue() ?: return
        playbackSessionStore.save(
            snapshot = snapshot.copy(
                currentQueueEntryId = currentQueueEntryIdOverride
                    ?: playerRepository.currentQueueEntryIdValue()
                    ?: snapshot.currentQueueEntryId,
            ),
            positionMs = positionMs.coerceAtLeast(0L),
            playWhenReady = playWhenReady,
            playMode = playerRepository.playMode.value,
        )
    }

    fun clearPlaybackState() {
        playerRepository.resetCurrent()
        playbackSessionStore.clear()
    }

    fun seekAdjacentMediaItem(
        player: Player,
        direction: Int,
    ): Boolean {
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

    suspend fun restorePersistedSessionIfNeeded(
        player: Player,
    ): Boolean {
        if (playerRepository.playbackQueueValue() != null || playerRepository.music.value != null) {
            return false
        }
        val persisted = playbackSessionStore.load() ?: return false
        val restoredPlayMode = runCatching { PlayMode.valueOf(persisted.playMode) }.getOrDefault(PlayMode.SINGLE)
        if (restoredPlayMode != playerRepository.playMode.value) {
            bridge.runSync { backend -> ctsSavePreferencePlaymode(backend, restoredPlayMode) }
            playerRepository.reload()
        }

        when (persisted.contextType) {
            PlaybackContextType.USER_PLAYLIST.name -> {
                val playlistId = persisted.playlistId?.let(::PlaylistId)
                val context = PlaybackContext(
                    type = PlaybackContextType.USER_PLAYLIST,
                    playlistId = playlistId,
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
                                sourceContext = buildPersistedSourceContext(persistedEntry),
                            )
                        )
                    }
                }
                if (entries.isEmpty()) {
                    playbackSessionStore.clear()
                    return false
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = persisted.currentQueueEntryId,
                )
                val entry = snapshot.currentEntry() ?: entries.firstOrNull() ?: return false
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return false
                val sourcePlaylist = playlistId?.let { id ->
                    bridge.run { ctGetPlaylist(it, id) }
                }
                playResolvedQueue(
                    player = player,
                    snapshot = snapshot.copy(currentQueueEntryId = entry.queueEntryId),
                    currentMusic = music,
                    currentQueueEntryId = entry.queueEntryId,
                    startPositionMs = persisted.positionMs,
                    playWhenReady = persisted.playWhenReady,
                    sourcePlaylist = sourcePlaylist,
                )
            }

            PlaybackContextType.FOLDER.name -> {
                val storageId = persisted.storageId?.let(::StorageId) ?: return false
                val folderPath = persisted.folderPath ?: return false
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
                    return false
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = persisted.currentQueueEntryId,
                )
                val entry = snapshot.currentEntry() ?: entries.firstOrNull() ?: return false
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return false
                playResolvedQueue(
                    player = player,
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
                    return false
                }
                val snapshot = PlaybackQueueSnapshot(
                    context = context,
                    entries = entries,
                    currentQueueEntryId = persisted.currentQueueEntryId,
                )
                val entry = snapshot.currentEntry() ?: entries.firstOrNull() ?: return false
                val music = bridge.run { ctGetMusic(it, entry.musicId) } ?: return false
                playResolvedQueue(
                    player = player,
                    snapshot = snapshot.copy(currentQueueEntryId = entry.queueEntryId),
                    currentMusic = music,
                    currentQueueEntryId = entry.queueEntryId,
                    startPositionMs = persisted.positionMs,
                    playWhenReady = persisted.playWhenReady,
                )
            }

            else -> return false
        }

        return true
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
}
