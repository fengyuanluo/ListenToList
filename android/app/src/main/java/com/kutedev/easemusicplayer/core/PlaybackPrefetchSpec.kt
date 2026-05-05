package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.datasource.DataSpec

internal data class PlaybackPrefetchSpec(
    val dataSpec: DataSpec,
    val cacheKey: String,
)

internal fun buildPlaybackPrefetchSpec(uri: Uri, bytes: Long): PlaybackPrefetchSpec? {
    if (bytes <= 0) {
        return null
    }
    val cacheKey = buildPlaybackMusicCacheKey(uri) ?: return null
    val dataSpec = DataSpec.Builder()
        .setUri(uri)
        .setPosition(0)
        .setLength(bytes)
        .setKey(cacheKey)
        .build()
    return PlaybackPrefetchSpec(dataSpec = dataSpec, cacheKey = cacheKey)
}
