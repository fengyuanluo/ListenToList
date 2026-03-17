package com.kutedev.easemusicplayer.singleton

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

sealed interface PlaybackCommand {
    data class PlayPlaylistMusic(
        val playlistId: PlaylistId,
        val musicId: MusicId,
        val direction: Int,
    ) : PlaybackCommand

    data class PlayFolder(
        val storageId: StorageId,
        val folderPath: String,
        val songs: List<StorageEntry>,
        val targetEntryPath: String,
        val ensuredMusics: List<AddedMusic>? = null,
    ) : PlaybackCommand

    data class PlayQueueEntry(
        val queueEntryId: String,
    ) : PlaybackCommand

    data class AppendEntries(
        val entries: List<StorageEntry>,
    ) : PlaybackCommand

    data class MoveQueueEntry(
        val fromIndex: Int,
        val toIndex: Int,
    ) : PlaybackCommand

    data class RemoveQueueEntry(
        val queueEntryId: String,
    ) : PlaybackCommand

    data object RemoveCurrent : PlaybackCommand

    data class RefreshPlaylistIfMatch(
        val playlist: Playlist,
    ) : PlaybackCommand

    data object RestorePersistedSessionIfNeeded : PlaybackCommand
}

@Singleton
class PlaybackCommandBus @Inject constructor(
    private val appScope: CoroutineScope,
) {
    private val channel = Channel<PlaybackCommand>(capacity = Channel.BUFFERED)

    val commands = channel.receiveAsFlow()

    fun dispatch(command: PlaybackCommand) {
        appScope.launch {
            channel.send(command)
        }
    }
}
