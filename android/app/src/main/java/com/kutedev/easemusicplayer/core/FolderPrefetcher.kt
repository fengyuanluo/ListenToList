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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uniffi.ease_client_backend.easeError
import java.util.Collections
import kotlin.math.min


class FolderPrefetcher(
    private val cache: Cache,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 2,
) {
    private val writers = Collections.synchronizedList(mutableListOf<CacheWriter>())
    private var prefetchJob: Job? = null
    private val cacheKeyFactory = CacheKeyFactory.DEFAULT

    fun prefetch(items: List<Pair<Uri, Long>>) {
        cancel()
        if (items.isEmpty()) {
            return
        }
        val semaphore = Semaphore(maxConcurrent)
        prefetchJob = scope.launch(Dispatchers.IO) {
            for ((uri, bytes) in items) {
                if (bytes <= 0) {
                    continue
                }
                val baseSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(0)
                    .setLength(bytes)
                    .build()
                val cacheKey = cacheKeyFactory.buildCacheKey(baseSpec)
                val resolvedLength = resolvePrefetchLength(cacheKey, baseSpec)
                if (resolvedLength <= 0) {
                    continue
                }
                val dataSpec = if (resolvedLength == baseSpec.length) {
                    baseSpec
                } else {
                    baseSpec.buildUpon().setLength(resolvedLength).build()
                }
                if (isCached(cacheKey, dataSpec)) {
                    continue
                }
                launch {
                    semaphore.withPermit {
                        val dataSource = cacheDataSourceFactory.createDataSource()
                        val writer = CacheWriter(dataSource, dataSpec, null, null)
                        writers.add(writer)
                        try {
                            writer.cache()
                        } catch (e: Exception) {
                            easeError("folder prefetch failed: $e")
                        } finally {
                            writers.remove(writer)
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        writers.forEach { it.cancel() }
        writers.clear()
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
