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

    @Test
    fun resolveCenteredLyricIndexIgnoresSpacerItemsAndFindsNearestLyric() {
        val centeredIndex = resolveCenteredLyricIndex(
            viewportStartOffset = 0,
            viewportEndOffset = 1000,
            visibleItems = listOf(
                LyricViewportItem(index = 0, offset = 0, size = 500),
                LyricViewportItem(index = 1, offset = 440, size = 96),
                LyricViewportItem(index = 2, offset = 560, size = 92),
                LyricViewportItem(index = 3, offset = 688, size = 88),
            ),
            lyricCount = 3,
        )

        assertEquals(0, centeredIndex)
    }

    @Test
    fun resolveCenteredLyricIndexSkipsBottomSpacerAndClampsToLyricsOnly() {
        val centeredIndex = resolveCenteredLyricIndex(
            viewportStartOffset = 0,
            viewportEndOffset = 1000,
            visibleItems = listOf(
                LyricViewportItem(index = 2, offset = 308, size = 84),
                LyricViewportItem(index = 3, offset = 458, size = 84),
                LyricViewportItem(index = 4, offset = 542, size = 500),
            ),
            lyricCount = 3,
        )

        assertEquals(2, centeredIndex)
    }

    @Test
    fun resolveCenteredLyricIndexReturnsNullWithoutVisibleLyrics() {
        val centeredIndex = resolveCenteredLyricIndex(
            viewportStartOffset = 0,
            viewportEndOffset = 1000,
            visibleItems = listOf(
                LyricViewportItem(index = 0, offset = 0, size = 500),
                LyricViewportItem(index = 4, offset = 500, size = 500),
            ),
            lyricCount = 3,
        )

        assertEquals(null, centeredIndex)
    }
}
