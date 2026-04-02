package com.kutedev.easemusicplayer.singleton

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcApiQueryResolverTest {
    @Test
    fun buildQueryPrefersEmbeddedMetadata() {
        val query = buildLrcApiQuerySpec(
            fallbackTitle = "track.mp3",
            fallbackPath = "/storage/emulated/0/Music/track.mp3",
            metadataHint = LrcApiMetadataHint(
                title = "Real Title",
                artist = "Real Artist",
                album = "Real Album",
            ),
        )

        assertEquals("Real Title", query.title)
        assertEquals("Real Artist", query.artist)
        assertEquals("Real Album", query.album)
    }

    @Test
    fun buildQueryStripsKnownAudioExtensionFromFallbackTitle() {
        val query = buildLrcApiQuerySpec(
            fallbackTitle = "彩虹.mp3",
            fallbackPath = "/storage/emulated/0/Music/彩虹.mp3",
            metadataHint = null,
        )

        assertEquals("彩虹", query.title)
        assertEquals("", query.artist)
        assertEquals("", query.album)
    }

    @Test
    fun buildQueryFallsBackToFilenameWhenTitleIsBlank() {
        val query = buildLrcApiQuerySpec(
            fallbackTitle = "   ",
            fallbackPath = "/storage/emulated/0/Music/Beyond - 海阔天空.flac",
            metadataHint = LrcApiMetadataHint(
                artist = "Beyond",
                album = "海阔天空",
            ),
        )

        assertEquals("Beyond - 海阔天空", query.title)
        assertEquals("Beyond", query.artist)
        assertEquals("海阔天空", query.album)
    }
}
