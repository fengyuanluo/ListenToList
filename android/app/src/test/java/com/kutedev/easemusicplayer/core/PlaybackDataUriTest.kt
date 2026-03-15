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
class PlaybackDataUriTest {
    @Test
    fun buildAndParsePlaybackUri_roundTripsMusicId() {
        val uri = buildPlaybackMusicUri(42L)

        assertEquals("ease", uri.scheme)
        assertEquals("data", uri.authority)
        assertEquals(42L, parsePlaybackMusicIdValue(uri))
    }

    @Test
    fun parsePlaybackMusicId_returnsNullForUnexpectedUri() {
        assertNull(parsePlaybackMusicIdValue(Uri.parse("https://example.com/audio.mp3")))
        assertNull(parsePlaybackMusicIdValue(Uri.parse("ease://other?music=1")))
        assertNull(parsePlaybackMusicIdValue(Uri.parse("ease://data?music=abc")))
    }
}
