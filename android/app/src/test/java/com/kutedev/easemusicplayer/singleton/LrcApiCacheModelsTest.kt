package com.kutedev.easemusicplayer.singleton

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ease_client_backend.LrcApiFetchResult
import uniffi.ease_client_backend.LrcApiFetchStatus

class LrcApiCacheModelsTest {
    @Test
    fun cachedFetchResultPreservesCoverBytes() {
        val cover = byteArrayOf(1, 2, 3, 4)
        val result = LrcApiFetchResult(
            lyrics = null,
            lyricsStatus = LrcApiFetchStatus.MISSING,
            cover = cover,
            coverStatus = LrcApiFetchStatus.LOADED,
        )

        val restored = result.toCachedResult().toFetchResult()

        assertEquals(LrcApiFetchStatus.LOADED, restored.coverStatus)
        assertArrayEquals(cover, restored.cover)
    }
}
