package com.kutedev.easemusicplayer.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicDisplayUtilsTest {
    @Test
    fun resolveMusicDisplayTitlePrefersEmbeddedMetadata() {
        val title = resolveMusicDisplayTitle(
            metadataTitle = "真正的歌名",
            path = "/storage/emulated/0/Music/fallback-track.flac",
            storedTitle = "fallback-track.flac",
        )

        assertEquals("真正的歌名", title)
    }

    @Test
    fun resolveMusicDisplayTitleFallsBackToFilenameWithoutSuffix() {
        val title = resolveMusicDisplayTitle(
            metadataTitle = null,
            path = "/storage/emulated/0/Music/Beyond - 海阔天空.flac",
            storedTitle = "Beyond - 海阔天空.flac",
        )

        assertEquals("Beyond - 海阔天空", title)
    }

    @Test
    fun fallbackMusicDisplayTitleUsesStoredTitleWhenPathMissing() {
        val title = fallbackMusicDisplayTitle(
            path = null,
            storedTitle = "track-name.m4a",
        )

        assertEquals("track-name", title)
    }
}
