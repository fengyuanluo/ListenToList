package com.kutedev.easemusicplayer.widgets.musics

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageSeekIndicatorLayoutTest {

    @Test
    fun resolveLyricSeekIndicatorLabelStartXCentersWithinAvailableGapWhenSpaceAllows() {
        val startX = resolveLyricSeekIndicatorLabelStartX(
            indicatorAnchorX = 420f,
            labelRightBoundaryX = 920f,
            labelWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(abs(startX - 610f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekIndicatorLabelStartXFallsBackRightwardWithoutCrashingWhenSpaceIsTight() {
        val startX = resolveLyricSeekIndicatorLabelStartX(
            indicatorAnchorX = 760f,
            labelRightBoundaryX = 900f,
            labelWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(abs(startX - 780f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekIndicatorLabelStartXReturnsNanForInvalidInputs() {
        val startX = resolveLyricSeekIndicatorLabelStartX(
            indicatorAnchorX = Float.NaN,
            labelRightBoundaryX = 900f,
            labelWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
        )

        assertTrue(startX.isNaN())
    }

    @Test
    fun resolveLyricSeekIndicatorLabelStartXCanBiasSlightlyRightWithinBounds() {
        val startX = resolveLyricSeekIndicatorLabelStartX(
            indicatorAnchorX = 420f,
            labelRightBoundaryX = 920f,
            labelWidthPx = 120,
            seekLineGapPx = 8f,
            seekTextGapPx = 16f,
            rightBiasPx = 12f,
        )

        assertTrue(abs(startX - 622f) < 0.001f)
    }
}
