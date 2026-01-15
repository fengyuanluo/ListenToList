package com.kutedev.easemusicplayer.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackCacheTest {
    @Test
    fun getCache_shouldReturnSingletonInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = PlaybackCache.getCache(context)
        val second = PlaybackCache.getCache(context)
        assertSame(first, second)
    }
}
