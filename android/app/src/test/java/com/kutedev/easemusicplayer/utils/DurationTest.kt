package com.kutedev.easemusicplayer.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class DurationTest {
    @Test
    fun formatDurationReturnsPlaceholderWhenNull() {
        val duration: Duration? = null
        assertEquals("--:--:--", formatDuration(duration))
    }

    @Test
    fun formatDurationFormatsSeconds() {
        assertEquals("00:01:05", formatDuration(Duration.ofSeconds(65)))
    }

    @Test
    fun toMusicDurationMsHandlesNull() {
        val duration: Duration? = null
        assertEquals(0uL, toMusicDurationMs(duration))
    }

    @Test
    fun resolveTotalDurationPrefersRuntime() {
        val runtime = Duration.ofSeconds(10)
        val meta = Duration.ofSeconds(5)
        assertEquals(runtime, resolveTotalDuration(runtime, meta))
    }

    @Test
    fun resolveTotalDurationFallsBackToMeta() {
        val meta = Duration.ofSeconds(8)
        assertEquals(meta, resolveTotalDuration(null, meta))
    }
}
