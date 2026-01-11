package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.easeError

class PlaybackPrefetcher(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope
) {
    private var prefetchJob: Job? = null
    private var writer: CacheWriter? = null

    fun prefetch(uri: Uri, bytes: Long) {
        cancel()
        if (bytes <= 0) {
            return
        }

        prefetchJob = scope.launch(Dispatchers.IO) {
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(bytes)
                .build()
            val dataSource = cacheDataSourceFactory.createDataSource()
            val cacheWriter = CacheWriter(dataSource, dataSpec, null, null)
            writer = cacheWriter

            try {
                cacheWriter.cache()
            } catch (e: Exception) {
                easeError("prefetch cache failed: $e")
            }
        }
    }

    fun cancel() {
        writer?.cancel()
        writer = null
        prefetchJob?.cancel()
        prefetchJob = null
    }
}
