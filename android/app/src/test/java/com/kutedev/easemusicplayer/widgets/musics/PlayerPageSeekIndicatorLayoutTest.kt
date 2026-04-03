package com.kutedev.easemusicplayer.widgets.musics

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPageSeekIndicatorLayoutTest {

    @Test
    fun resolveFixedLyricSeekPreviewXAnchorsThePreviewToTheRightSide() {
        val startX = resolveFixedLyricSeekPreviewX(
            containerWidthPx = 920,
            contentWidthPx = 120,
            endPaddingPx = 8f,
        )

        assertTrue(abs(startX - 792f) < 0.001f)
    }

    @Test
    fun resolveFixedLyricSeekPreviewXReturnsNanForInvalidInputs() {
        val startX = resolveFixedLyricSeekPreviewX(
            containerWidthPx = 0,
            contentWidthPx = 120,
            endPaddingPx = 8f,
        )

        assertTrue(startX.isNaN())
    }

    @Test
    fun resolveLyricSeekPreviewCenterYStaysOnAnchorWhenThereIsEnoughSpace() {
        val centerY = resolveLyricSeekPreviewCenterY(
            anchorY = 240f,
            containerHeightPx = 600,
            contentHeightPx = 48,
        )

        assertTrue(abs(centerY - 240f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekPreviewCenterYClampsNearTheTopBoundary() {
        val centerY = resolveLyricSeekPreviewCenterY(
            anchorY = 10f,
            containerHeightPx = 600,
            contentHeightPx = 48,
        )

        assertTrue(abs(centerY - 24f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekPreviewCenterYClampsNearTheBottomBoundary() {
        val centerY = resolveLyricSeekPreviewCenterY(
            anchorY = 590f,
            containerHeightPx = 600,
            contentHeightPx = 48,
        )

        assertTrue(abs(centerY - 576f) < 0.001f)
    }

    @Test
    fun resolveLyricSeekPreviewCenterYReturnsNanForInvalidInputs() {
        val centerY = resolveLyricSeekPreviewCenterY(
            anchorY = Float.NaN,
            containerHeightPx = 600,
            contentHeightPx = 48,
        )

        assertTrue(centerY.isNaN())
    }
}
