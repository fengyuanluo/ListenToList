package com.kutedev.easemusicplayer.core

data class PlaybackRouteSnapshot(
    val musicId: Long? = null,
    val route: String? = null,
    val resolvedUri: String? = null,
    val sourceTag: String? = null,
)

const val PLAYBACK_ROUTE_DIRECT_HTTP = "direct_http"
const val PLAYBACK_ROUTE_LOCAL_FILE = "local_file"
const val PLAYBACK_ROUTE_DOWNLOADED_FILE = "downloaded_file"
const val PLAYBACK_ROUTE_DOWNLOADED_CONTENT = "downloaded_content"
const val PLAYBACK_ROUTE_STREAM_FALLBACK = "stream_fallback"

object PlaybackDiagnostics {
    @Volatile
    private var snapshot = PlaybackRouteSnapshot()
    private val history = mutableListOf<PlaybackRouteSnapshot>()

    fun reset() {
        synchronized(history) {
            snapshot = PlaybackRouteSnapshot()
            history.clear()
        }
    }

    fun record(
        musicId: Long,
        route: String,
        resolvedUri: String?,
        sourceTag: String,
    ) {
        val next = PlaybackRouteSnapshot(
            musicId = musicId,
            route = route,
            resolvedUri = resolvedUri,
            sourceTag = sourceTag,
        )
        synchronized(history) {
            snapshot = next
            history += next
            if (history.size > 64) {
                history.removeAt(0)
            }
        }
    }

    fun currentSnapshot(): PlaybackRouteSnapshot {
        return snapshot
    }

    fun historySnapshot(): List<PlaybackRouteSnapshot> {
        return synchronized(history) {
            history.toList()
        }
    }
}
