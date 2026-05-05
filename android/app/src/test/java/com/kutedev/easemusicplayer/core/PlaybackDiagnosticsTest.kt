package com.kutedev.easemusicplayer.core

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackDiagnosticsTest {
    @Before
    fun resetDiagnostics() {
        PlaybackDiagnostics.reset()
    }

    @Test
    fun routeRecordCarriesRecoveryCountersForward() {
        val error = PlaybackException(
            "network timeout",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
        PlaybackDiagnostics.recordRouteRefresh(error, musicId = 12L)
        PlaybackDiagnostics.recordRecoverySkip(error, musicId = 12L)
        PlaybackDiagnostics.recordCacheBypass(reason = 1)
        PlaybackDiagnostics.recordMetadataFailure(musicId = 12L, stage = "timeout", error = error)

        PlaybackDiagnostics.record(
            musicId = 13L,
            route = PLAYBACK_ROUTE_DIRECT_HTTP,
            resolvedUri = "https://example.com/next.wav",
            sourceTag = PLAYBACK_SOURCE_TAG_PLAYBACK,
        )

        val snapshot = PlaybackDiagnostics.currentSnapshot()
        assertEquals(13L, snapshot.musicId)
        assertEquals(PLAYBACK_ROUTE_DIRECT_HTTP, snapshot.route)
        assertEquals(1, snapshot.routeRefreshCount)
        assertEquals(1, snapshot.recoverySkipCount)
        assertEquals(1, snapshot.cacheBypassCount)
        assertEquals(1, snapshot.lastCacheBypassReason)
        assertEquals(1, snapshot.metadataFailureCount)
        assertEquals(12L, snapshot.lastMetadataFailureMusicId)
        assertEquals("timeout", snapshot.lastMetadataFailureStage)
        assertEquals("PlaybackException: network timeout", snapshot.lastMetadataFailureMessage)
        assertEquals(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            snapshot.lastPlaybackErrorCode,
        )
        assertEquals(
            PlaybackException.getErrorCodeName(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            snapshot.lastPlaybackErrorName,
        )
    }

    @Test
    fun resetClearsRecoverySignals() {
        val error = PlaybackException(
            "network timeout",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
        PlaybackDiagnostics.recordRouteRefresh(error, musicId = 12L)

        PlaybackDiagnostics.reset()

        val snapshot = PlaybackDiagnostics.currentSnapshot()
        assertNull(snapshot.musicId)
        assertEquals(0, snapshot.routeRefreshCount)
        assertEquals(0, snapshot.recoverySkipCount)
        assertEquals(0, snapshot.cacheBypassCount)
        assertEquals(0, snapshot.metadataFailureCount)
        assertNull(snapshot.lastPlaybackErrorCode)
        assertNull(snapshot.lastPlaybackErrorName)
        assertNull(snapshot.lastCacheBypassReason)
        assertNull(snapshot.lastMetadataFailureMusicId)
        assertNull(snapshot.lastMetadataFailureStage)
        assertNull(snapshot.lastMetadataFailureMessage)
        assertEquals(emptyList<PlaybackRouteSnapshot>(), PlaybackDiagnostics.historySnapshot())
    }

    @Test
    fun cacheBypassRecordsReasonAndHistory() {
        PlaybackDiagnostics.recordCacheBypass(reason = 1)
        PlaybackDiagnostics.recordCacheBypass(reason = 2)

        val snapshot = PlaybackDiagnostics.currentSnapshot()
        assertEquals(2, snapshot.cacheBypassCount)
        assertEquals(2, snapshot.lastCacheBypassReason)
        assertEquals(2, PlaybackDiagnostics.historySnapshot().size)
    }

    @Test
    fun metadataFailureRecordsFailureDetails() {
        PlaybackDiagnostics.recordMetadataFailure(
            musicId = 21L,
            stage = "player_error",
            error = IllegalStateException("metadata player failed"),
        )

        val snapshot = PlaybackDiagnostics.currentSnapshot()
        assertEquals(21L, snapshot.musicId)
        assertEquals(1, snapshot.metadataFailureCount)
        assertEquals(21L, snapshot.lastMetadataFailureMusicId)
        assertEquals("player_error", snapshot.lastMetadataFailureStage)
        assertEquals(
            "IllegalStateException: metadata player failed",
            snapshot.lastMetadataFailureMessage,
        )
    }
}
