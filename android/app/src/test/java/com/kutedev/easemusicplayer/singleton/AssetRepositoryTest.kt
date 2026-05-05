package com.kutedev.easemusicplayer.singleton

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetRepositoryTest {
    @Test
    fun calculateBitmapSampleSizeKeepsSmallOrUnboundedImagesAtOriginalSize() {
        assertEquals(
            1,
            calculateBitmapSampleSize(
                sourceWidth = 400,
                sourceHeight = 300,
                maxWidthPx = null,
                maxHeightPx = null,
            )
        )
        assertEquals(
            1,
            calculateBitmapSampleSize(
                sourceWidth = 400,
                sourceHeight = 300,
                maxWidthPx = 600,
                maxHeightPx = 600,
            )
        )
    }

    @Test
    fun calculateBitmapSampleSizeDownsamplesToLargestUsefulPowerOfTwo() {
        assertEquals(
            4,
            calculateBitmapSampleSize(
                sourceWidth = 4000,
                sourceHeight = 3000,
                maxWidthPx = 700,
                maxHeightPx = 700,
            )
        )
        assertEquals(
            8,
            calculateBitmapSampleSize(
                sourceWidth = 4096,
                sourceHeight = 4096,
                maxWidthPx = 512,
                maxHeightPx = 512,
            )
        )
    }

    @Test
    fun calculateBitmapSampleSizeHandlesOneDimensionalConstraints() {
        assertEquals(
            4,
            calculateBitmapSampleSize(
                sourceWidth = 4000,
                sourceHeight = 2000,
                maxWidthPx = 900,
                maxHeightPx = null,
            )
        )
        assertEquals(
            2,
            calculateBitmapSampleSize(
                sourceWidth = 4000,
                sourceHeight = 2000,
                maxWidthPx = null,
                maxHeightPx = 900,
            )
        )
    }
}
