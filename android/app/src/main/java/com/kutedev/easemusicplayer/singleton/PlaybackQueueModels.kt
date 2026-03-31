package com.kutedev.easemusicplayer.singleton

import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageId

enum class PlaybackContextType {
    USER_PLAYLIST,
    FOLDER,
    TEMPORARY,
}

data class PlaybackContext(
    val type: PlaybackContextType,
    val playlistId: PlaylistId? = null,
    val storageId: StorageId? = null,
    val folderPath: String? = null,
)

data class PlaybackQueueEntry(
    val queueEntryId: String,
    val musicId: MusicId,
    val musicAbstract: MusicAbstract,
    val sourceContext: PlaybackContext,
)

data class PlaybackQueueSnapshot(
    val context: PlaybackContext,
    val entries: List<PlaybackQueueEntry>,
    val currentQueueEntryId: String,
) {
    fun currentEntry(): PlaybackQueueEntry? = entries.firstOrNull { it.queueEntryId == currentQueueEntryId }

    fun indexOf(queueEntryId: String?): Int {
        if (queueEntryId == null) {
            return -1
        }
        return entries.indexOfFirst { it.queueEntryId == queueEntryId }
    }
}

fun buildPlaylistQueueEntryId(
    playlistId: PlaylistId,
    musicId: MusicId,
): String {
    return "playlist:${playlistId.value}:${musicId.value}"
}

fun buildFolderQueueEntryId(
    storageId: StorageId,
    folderPath: String,
    musicId: MusicId,
    index: Int,
): String {
    return "folder:${storageId.value}:$folderPath:${musicId.value}:$index"
}

fun buildTemporaryQueueEntryId(
    storageId: StorageId,
    path: String,
    musicId: MusicId,
    nonce: Long,
    index: Int,
): String {
    return "temporary:${storageId.value}:$path:${musicId.value}:$nonce:$index"
}
