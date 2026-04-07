package com.kutedev.easemusicplayer.widgets.musics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekBarUtilsTest {
    @Test
    fun resolveSeekPositionFromOffsetUsesAbsoluteTrackPosition() {
        val target = resolveSeekPositionFromOffset(
            offsetPx = 240f,
            widthPx = 300,
            totalDurationMs = 100_000uL,
        )

        assertEquals(80_000uL, target)
    }

    @Test
    fun resolveSeekPositionFromOffsetClampsOutOfBoundsOffsets() {
        assertEquals(
            0uL,
            resolveSeekPositionFromOffset(
                offsetPx = -20f,
                widthPx = 300,
                totalDurationMs = 100_000uL,
            )
        )
        assertEquals(
            100_000uL,
            resolveSeekPositionFromOffset(
                offsetPx = 500f,
                widthPx = 300,
                totalDurationMs = 100_000uL,
            )
        )
    }

    @Test
    fun resolveSeekRatioClampsReportedProgressToTrackBounds() {
        assertEquals(0f, resolveSeekRatio(0uL, 100_000uL))
        assertEquals(1f, resolveSeekRatio(200_000uL, 100_000uL))
    }

    @Test
    fun formatProgressDurationReturnsPlaceholderWhenUnknown() {
        assertEquals("--:--:--", formatProgressDuration(null))
    }

    @Test
    fun shouldClearOptimisticSeekReturnsTrueWhenReportedPositionSettlesNearTarget() {
        assertTrue(
            shouldClearOptimisticSeek(
                reportedPositionMs = 80_450uL,
                optimisticPositionMs = 80_000uL,
            )
        )
        assertFalse(
            shouldClearOptimisticSeek(
                reportedPositionMs = 70_000uL,
                optimisticPositionMs = 80_000uL,
            )
        )
    }
}
