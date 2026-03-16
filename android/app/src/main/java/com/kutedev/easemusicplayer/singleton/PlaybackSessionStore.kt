package com.kutedev.easemusicplayer.singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uniffi.ease_client_schema.PlayMode

@Serializable
data class PersistedPlaybackQueueEntry(
    val queueEntryId: String,
    val musicId: Long,
    val sourceType: String,
    val playlistId: Long? = null,
    val storageId: Long? = null,
    val folderPath: String? = null,
)

@Serializable
data class PersistedPlaybackSession(
    val contextType: String,
    val playlistId: Long? = null,
    val storageId: Long? = null,
    val folderPath: String? = null,
    val entries: List<PersistedPlaybackQueueEntry>,
    val currentQueueEntryId: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val playMode: String,
)

@Singleton
class PlaybackSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val PREFS_NAME = "playback_session"
        private const val KEY_SESSION_JSON = "session_json"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(
        snapshot: PlaybackQueueSnapshot,
        positionMs: Long,
        playWhenReady: Boolean,
        playMode: PlayMode,
    ) {
        val persisted = PersistedPlaybackSession(
            contextType = snapshot.context.type.name,
            playlistId = snapshot.context.playlistId?.value,
            storageId = snapshot.context.storageId?.value,
            folderPath = snapshot.context.folderPath,
            entries = snapshot.entries.map { entry ->
                PersistedPlaybackQueueEntry(
                    queueEntryId = entry.queueEntryId,
                    musicId = entry.musicId.value,
                    sourceType = entry.sourceContext.type.name,
                    playlistId = entry.sourceContext.playlistId?.value,
                    storageId = entry.sourceContext.storageId?.value,
                    folderPath = entry.sourceContext.folderPath,
                )
            },
            currentQueueEntryId = snapshot.currentQueueEntryId,
            positionMs = positionMs.coerceAtLeast(0L),
            playWhenReady = playWhenReady,
            playMode = playMode.name,
        )
        prefs.edit()
            .putString(KEY_SESSION_JSON, json.encodeToString(PersistedPlaybackSession.serializer(), persisted))
            .apply()
    }

    fun load(): PersistedPlaybackSession? {
        val raw = prefs.getString(KEY_SESSION_JSON, null) ?: return null
        return runCatching {
            json.decodeFromString(PersistedPlaybackSession.serializer(), raw)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION_JSON).apply()
    }
}
