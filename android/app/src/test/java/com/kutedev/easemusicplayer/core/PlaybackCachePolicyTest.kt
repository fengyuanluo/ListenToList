package com.kutedev.easemusicplayer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCachePolicyTest {
    @Test
    fun prefetchBytesByPercentHonorsMin() {
        val bytes = PlaybackCachePolicy.prefetchBytesByPercent(1_000_000L, 0.1f)
        val expectedMin = 512L * 1024
        assertEquals(expectedMin, bytes)
    }

    @Test
    fun prefetchBytesByPercentHonorsMax() {
        val bytes = PlaybackCachePolicy.prefetchBytesByPercent(500L * 1024 * 1024, 0.1f)
        val expectedMax = 20L * 1024 * 1024
        assertEquals(expectedMax, bytes)
    }

    @Test
    fun prefetchBytesByPercentUsesRatio() {
        val fileSize = 30L * 1024 * 1024
        val bytes = PlaybackCachePolicy.prefetchBytesByPercent(fileSize, 0.1f)
        assertEquals(3L * 1024 * 1024, bytes)
    }

    @Test
    fun prefetchBytesForSecondsNeverBelowMin() {
        val bytes = PlaybackCachePolicy.prefetchBytesForSeconds(1)
        assertTrue(bytes >= 512L * 1024)
    }

    @Test
    fun prefetchBytesByPercentNeverExceedsFileSize() {
        val bytes = PlaybackCachePolicy.prefetchBytesByPercent(200_000L, 0.1f)
        assertEquals(200_000L, bytes)
    }
}
