package com.kutedev.easemusicplayer.core

import androidx.media3.datasource.DataSource
import com.kutedev.easemusicplayer.singleton.Bridge
import kotlinx.coroutines.CoroutineScope

object PlaybackDataSourceFactory {
    fun create(
        bridge: Bridge,
        scope: CoroutineScope,
        sourceTag: String = PLAYBACK_SOURCE_TAG_PLAYBACK,
    ): DataSource.Factory {
        return buildMusicPlaybackDataSourceFactory(
            bridge = bridge,
            scope = scope,
            sourceTag = sourceTag,
        )
    }
}
