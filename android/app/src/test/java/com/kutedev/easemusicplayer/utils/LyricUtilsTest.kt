package com.kutedev.easemusicplayer.utils

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ease_client_backend.LyricLine

class LyricUtilsTest {
    @Test
    fun resolveLyricIndexTracksDisplayedLyricsTimeline() {
        val lyrics = listOf(
            LyricLine(Duration.ofSeconds(1), "first"),
            LyricLine(Duration.ofSeconds(3), "second"),
            LyricLine(Duration.ofSeconds(5), "third"),
        )

        assertEquals(-1, resolveLyricIndex(Duration.ZERO, lyrics))
        assertEquals(0, resolveLyricIndex(Duration.ofSeconds(2), lyrics))
        assertEquals(1, resolveLyricIndex(Duration.ofSeconds(4), lyrics))
        assertEquals(2, resolveLyricIndex(Duration.ofSeconds(6), lyrics))
    }
}
