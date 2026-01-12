package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.easeError
import kotlin.math.min

class PlaybackPrefetcher(
    private val cache: Cache,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope
) {
    private var prefetchJob: Job? = null
    private var writer: CacheWriter? = null
    private val cacheKeyFactory = CacheKeyFactory.DEFAULT

    fun prefetch(uri: Uri, bytes: Long) {
        cancel()
        if (bytes <= 0) {
            return
        }

        prefetchJob = scope.launch(Dispatchers.IO) {
            val baseSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(bytes)
                .build()
            val cacheKey = cacheKeyFactory.buildCacheKey(baseSpec)
            val resolvedLength = resolvePrefetchLength(cacheKey, baseSpec)
            if (resolvedLength <= 0) {
                return@launch
            }
            val dataSpec = if (resolvedLength == baseSpec.length) {
                baseSpec
            } else {
                baseSpec.buildUpon().setLength(resolvedLength).build()
            }
            if (isCached(cacheKey, dataSpec)) {
                return@launch
            }
            val dataSource = cacheDataSourceFactory.createDataSource()
            val cacheWriter = CacheWriter(dataSource, dataSpec, null, null)
            writer = cacheWriter

            try {
                cacheWriter.cache()
            } catch (e: Exception) {
                easeError("prefetch cache failed: $e")
            } finally {
                if (prefetchJob == this.coroutineContext[Job]) {
                    writer = null
                    prefetchJob = null
                }
            }
        }
    }

    fun cancel() {
        writer?.cancel()
        writer = null
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun isCached(cacheKey: String, dataSpec: DataSpec): Boolean {
        if (dataSpec.length <= 0) {
            return false
        }
        return cache.isCached(cacheKey, dataSpec.position, dataSpec.length)
    }

    private fun resolvePrefetchLength(cacheKey: String, dataSpec: DataSpec): Long {
        if (dataSpec.length <= 0) {
            return dataSpec.length
        }
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        if (contentLength == C.LENGTH_UNSET.toLong()) {
            return dataSpec.length
        }
        val remaining = (contentLength - dataSpec.position).coerceAtLeast(0L)
        return min(dataSpec.length, remaining)
    }
}
