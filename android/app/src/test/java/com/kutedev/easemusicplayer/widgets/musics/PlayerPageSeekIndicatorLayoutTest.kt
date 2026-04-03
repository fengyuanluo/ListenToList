package com.kutedev.easemusicplayer.widgets.musics

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageSeekIndicatorLayoutTest {

    @Test
    fun resolveLyricSeekPreviewStartXCentersWithinAvailableGapWhenSpaceAllows() {
        val startX = resolveLyricSeekPreviewStartX(
            indicatorAnchorX = 420f,
            contentRightBoundaryX = 920f,
            contentWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(abs(startX - 610f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekPreviewStartXFallsBackRightwardWithoutCrashingWhenSpaceIsTight() {
        val startX = resolveLyricSeekPreviewStartX(
            indicatorAnchorX = 760f,
            contentRightBoundaryX = 900f,
            contentWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(abs(startX - 780f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekPreviewStartXReturnsNanForInvalidInputs() {
        val startX = resolveLyricSeekPreviewStartX(
            indicatorAnchorX = Float.NaN,
            contentRightBoundaryX = 900f,
            contentWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(startX.isNaN())
    }

    @Test
    fun resolveLyricSeekPreviewStartXCanBiasSlightlyRightWithinBounds() {
        val startX = resolveLyricSeekPreviewStartX(
            indicatorAnchorX = 420f,
            contentRightBoundaryX = 920f,
            contentWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
            rightBiasPx = 12f,
        )

        assertTrue(abs(startX - 622f) < 0.001f)
    }
}
