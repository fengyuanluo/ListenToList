package com.kutedev.easemusicplayer.core

import android.content.Context
import androidx.media3.datasource.DataSource
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import kotlinx.coroutines.CoroutineScope

object PlaybackDataSourceFactory {
    fun create(
        appContext: Context,
        bridge: Bridge,
        downloadRepository: DownloadRepository,
        scope: CoroutineScope,
        sourceTag: String = PLAYBACK_SOURCE_TAG_PLAYBACK,
    ): DataSource.Factory {
        return buildMusicPlaybackDataSourceFactory(
            appContext = appContext,
            bridge = bridge,
            downloadRepository = downloadRepository,
            scope = scope,
            sourceTag = sourceTag,
        )
    }
}
