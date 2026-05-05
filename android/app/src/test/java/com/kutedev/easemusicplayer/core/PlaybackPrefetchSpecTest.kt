package com.kutedev.easemusicplayer.core

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackPrefetchSpecTest {
    @Test
    fun buildPlaybackPrefetchSpec_usesPlaybackMusicCacheKey() {
        val spec = buildPlaybackPrefetchSpec(Uri.parse("ease://data?music=88"), bytes = 4096L)

        assertEquals("music:88", spec?.cacheKey)
        assertEquals("music:88", spec?.dataSpec?.key)
        assertEquals(4096L, spec?.dataSpec?.length)
        assertEquals(0L, spec?.dataSpec?.position)
    }

    @Test
    fun buildPlaybackPrefetchSpec_rejectsUnsupportedUriAndEmptyLength() {
        assertNull(buildPlaybackPrefetchSpec(Uri.parse("https://example.com/music.wav"), bytes = 4096L))
        assertNull(buildPlaybackPrefetchSpec(Uri.parse("ease://data?music=88"), bytes = 0L))
    }
}
