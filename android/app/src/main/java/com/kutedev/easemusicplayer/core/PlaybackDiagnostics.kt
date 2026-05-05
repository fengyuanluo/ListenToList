package com.kutedev.easemusicplayer.core

import androidx.media3.common.PlaybackException

data class PlaybackRouteSnapshot(
    val musicId: Long? = null,
    val route: String? = null,
    val resolvedUri: String? = null,
    val sourceTag: String? = null,
    val routeRefreshCount: Int = 0,
    val recoverySkipCount: Int = 0,
    val lastPlaybackErrorCode: Int? = null,
    val lastPlaybackErrorName: String? = null,
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
        val current = snapshot
        val next = PlaybackRouteSnapshot(
            musicId = musicId,
            route = route,
            resolvedUri = resolvedUri,
            sourceTag = sourceTag,
            routeRefreshCount = current.routeRefreshCount,
            recoverySkipCount = current.recoverySkipCount,
            lastPlaybackErrorCode = current.lastPlaybackErrorCode,
            lastPlaybackErrorName = current.lastPlaybackErrorName,
        )
        synchronized(history) {
            snapshot = next
            history += next
            if (history.size > 64) {
                history.removeAt(0)
            }
        }
    }

    fun recordRouteRefresh(
        error: PlaybackException,
        musicId: Long?,
    ) {
        recordRecoverySignal(
            error = error,
            musicId = musicId,
            incrementRouteRefresh = true,
            incrementSkip = false,
        )
    }

    fun recordRecoverySkip(
        error: PlaybackException,
        musicId: Long?,
    ) {
        recordRecoverySignal(
            error = error,
            musicId = musicId,
            incrementRouteRefresh = false,
            incrementSkip = true,
        )
    }

    fun currentSnapshot(): PlaybackRouteSnapshot {
        return snapshot
    }

    fun historySnapshot(): List<PlaybackRouteSnapshot> {
        return synchronized(history) {
            history.toList()
        }
    }

    private fun recordRecoverySignal(
        error: PlaybackException,
        musicId: Long?,
        incrementRouteRefresh: Boolean,
        incrementSkip: Boolean,
    ) {
        synchronized(history) {
            val current = snapshot
            val next = current.copy(
                musicId = musicId ?: current.musicId,
                routeRefreshCount = current.routeRefreshCount + if (incrementRouteRefresh) 1 else 0,
                recoverySkipCount = current.recoverySkipCount + if (incrementSkip) 1 else 0,
                lastPlaybackErrorCode = error.errorCode,
                lastPlaybackErrorName = error.getErrorCodeName(),
            )
            snapshot = next
            history += next
            if (history.size > 64) {
                history.removeAt(0)
            }
        }
    }
}
