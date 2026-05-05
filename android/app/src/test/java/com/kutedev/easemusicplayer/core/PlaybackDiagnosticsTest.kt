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
        assertNull(snapshot.lastPlaybackErrorCode)
        assertNull(snapshot.lastPlaybackErrorName)
        assertEquals(emptyList<PlaybackRouteSnapshot>(), PlaybackDiagnostics.historySnapshot())
    }
}
