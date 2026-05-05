package com.kutedev.easemusicplayer.core

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import uniffi.ease_client_backend.PlaybackSourceDescriptor
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctResolveMusicPlaybackSource
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.MusicId

const val PLAYBACK_SOURCE_TAG_PLAYBACK = "playback"
const val PLAYBACK_SOURCE_TAG_NEXT_PREFETCH = "next-prefetch"
const val PLAYBACK_SOURCE_TAG_FOLDER_PREFETCH = "folder-prefetch"
const val PLAYBACK_SOURCE_TAG_METADATA = "metadata"

private const val PLAYBACK_HTTP_USER_AGENT = "EaseMusicPlayer/1.0"
private const val MAX_DIRECT_HTTP_OPEN_RETRIES = 1
private const val PLAYBACK_RESOLVER_CACHE_MAX_ENTRIES = 256
private const val PLAYBACK_RESOLVER_DIRECT_HTTP_TTL_MS = 60_000L
private const val PLAYBACK_RESOLVER_STREAM_FALLBACK_TTL_MS = 15_000L
private const val PLAYBACK_RESOLVER_LOCAL_FILE_TTL_MS = 5 * 60_000L

data class MusicPlaybackHttpHeader(
    val name: String,
    val value: String,
)

sealed class ResolvedMusicPlaybackSource {
    data class DirectHttp(
        val url: String,
        val headers: List<MusicPlaybackHttpHeader>,
        val cacheKey: String?,
    ) : ResolvedMusicPlaybackSource()

    data class LocalFile(
        val absolutePath: String,
    ) : ResolvedMusicPlaybackSource()

    data class DownloadedFile(
        val absolutePath: String,
    ) : ResolvedMusicPlaybackSource()

    data class ContentUri(
        val uri: String,
    ) : ResolvedMusicPlaybackSource()

    data object StreamFallback : ResolvedMusicPlaybackSource()
}

fun interface MusicPlaybackSourceResolver {
    @Throws(IOException::class)
    fun resolve(musicId: MusicId): ResolvedMusicPlaybackSource?
}

object PlaybackSourceResolverCache {
    private data class CacheEntry(
        val resolved: ResolvedMusicPlaybackSource?,
        val expiresAtMs: Long,
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<Long, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CacheEntry>?): Boolean {
            return size > PLAYBACK_RESOLVER_CACHE_MAX_ENTRIES
        }
    }

    fun resolve(
        musicId: MusicId,
        loader: () -> ResolvedMusicPlaybackSource?,
    ): ResolvedMusicPlaybackSource? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val cached = entries[musicId.value]
            if (cached != null && cached.expiresAtMs > now) {
                return cached.resolved
            }
            if (cached != null) {
                entries.remove(musicId.value)
            }
        }

        val resolved = loader()
        val ttlMs = when (resolved) {
            is ResolvedMusicPlaybackSource.DirectHttp -> PLAYBACK_RESOLVER_DIRECT_HTTP_TTL_MS
            is ResolvedMusicPlaybackSource.LocalFile -> PLAYBACK_RESOLVER_LOCAL_FILE_TTL_MS
            is ResolvedMusicPlaybackSource.DownloadedFile -> PLAYBACK_RESOLVER_LOCAL_FILE_TTL_MS
            is ResolvedMusicPlaybackSource.ContentUri -> PLAYBACK_RESOLVER_LOCAL_FILE_TTL_MS
            ResolvedMusicPlaybackSource.StreamFallback, null -> PLAYBACK_RESOLVER_STREAM_FALLBACK_TTL_MS
        }
        synchronized(lock) {
            entries[musicId.value] = CacheEntry(
                resolved = resolved,
                expiresAtMs = now + ttlMs,
            )
        }
        return resolved
    }

    fun invalidate(musicId: MusicId) {
        synchronized(lock) {
            entries.remove(musicId.value)
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            entries.clear()
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

internal fun resolveMusicPlaybackSourceWithBridge(
    bridge: Bridge,
    musicId: MusicId,
    downloadRepository: DownloadRepository? = null,
): ResolvedMusicPlaybackSource? {
    if (downloadRepository != null) {
        val offlineResolved = runBlocking {
            bridge.run { backend ->
                ctGetMusic(backend, musicId)
            }?.let { music ->
                downloadRepository.resolveCompletedPlaybackSource(
                    storageId = music.loc.storageId.value,
                    sourcePath = music.loc.path,
                )
            }
        }
        if (offlineResolved != null) {
            return offlineResolved
        }
    }
    return PlaybackSourceResolverCache.resolve(musicId) {
        runBlocking {
            bridge.runRaw { backend ->
                ctResolveMusicPlaybackSource(backend, musicId)
                    ?.toResolvedMusicPlaybackSource()
            }
        }
    }
}

class MusicPlaybackDataSource(
    private val resolver: MusicPlaybackSourceResolver,
    private val httpDataSourceFactory: DataSource.Factory,
    private val fileDataSourceFactory: DataSource.Factory,
    private val contentDataSourceFactory: DataSource.Factory,
    private val streamFallbackFactory: DataSource.Factory,
    private val sourceTag: String = PLAYBACK_SOURCE_TAG_PLAYBACK,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var currentDelegate: DataSource? = null
    private var currentUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        currentDelegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val musicId = extractMusicId(dataSpec.uri)
        val playbackCacheKey = dataSpec.key ?: buildPlaybackMusicCacheKey(musicId)

        var attempt = 0
        while (true) {
            val resolved = resolver.resolve(musicId)
                ?: throw FileNotFoundException("music ${musicId.value} not found")

            val route = when (resolved) {
                is ResolvedMusicPlaybackSource.DirectHttp -> PLAYBACK_ROUTE_DIRECT_HTTP
                is ResolvedMusicPlaybackSource.LocalFile -> PLAYBACK_ROUTE_LOCAL_FILE
                is ResolvedMusicPlaybackSource.DownloadedFile -> PLAYBACK_ROUTE_DOWNLOADED_FILE
                is ResolvedMusicPlaybackSource.ContentUri -> PLAYBACK_ROUTE_DOWNLOADED_CONTENT
                ResolvedMusicPlaybackSource.StreamFallback -> PLAYBACK_ROUTE_STREAM_FALLBACK
            }

            val (delegate, delegateSpec) = when (resolved) {
                is ResolvedMusicPlaybackSource.DirectHttp -> {
                    val headers = linkedMapOf<String, String>()
                    headers.putAll(dataSpec.httpRequestHeaders)
                    resolved.headers.forEach { header ->
                        headers[header.name] = header.value
                    }
                    val builder = dataSpec.buildUpon()
                        .setUri(Uri.parse(resolved.url))
                        .setHttpRequestHeaders(headers)
                        .setKey(playbackCacheKey)
                    httpDataSourceFactory.createDataSource() to builder.build()
                }
                is ResolvedMusicPlaybackSource.LocalFile -> {
                    val spec = dataSpec.buildUpon()
                        .setUri(Uri.fromFile(File(resolved.absolutePath)))
                        .setKey(playbackCacheKey)
                        .build()
                    fileDataSourceFactory.createDataSource() to spec
                }
                is ResolvedMusicPlaybackSource.DownloadedFile -> {
                    val spec = dataSpec.buildUpon()
                        .setUri(Uri.fromFile(File(resolved.absolutePath)))
                        .setKey(playbackCacheKey)
                        .build()
                    fileDataSourceFactory.createDataSource() to spec
                }
                is ResolvedMusicPlaybackSource.ContentUri -> {
                    val resolvedUri = Uri.parse(resolved.uri)
                    val spec = dataSpec.buildUpon()
                        .setUri(resolvedUri)
                        .setKey(playbackCacheKey)
                        .build()
                    contentDataSourceFactory.createDataSource() to spec
                }
                ResolvedMusicPlaybackSource.StreamFallback -> {
                    val spec = dataSpec.buildUpon()
                        .setKey(playbackCacheKey)
                        .build()
                    streamFallbackFactory.createDataSource() to spec
                }
            }

            transferListeners.forEach(delegate::addTransferListener)
            currentDelegate = delegate
            currentUri = delegateSpec.uri
            PlaybackDiagnostics.record(
                musicId = musicId.value,
                route = route,
                resolvedUri = delegateSpec.uri.toString(),
                sourceTag = sourceTag,
            )
            safeEaseLog(
                "PLAYBACK_ROUTE source=$sourceTag route=$route music=${musicId.value} uri=${sanitizePlaybackRouteUriForLog(delegateSpec.uri)}"
            )

            try {
                return delegate.open(delegateSpec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                closeQuietly()
                if (
                    resolved is ResolvedMusicPlaybackSource.DirectHttp &&
                    (error.responseCode == 401 || error.responseCode == 403 || error.responseCode == 404) &&
                    attempt < MAX_DIRECT_HTTP_OPEN_RETRIES
                ) {
                    attempt += 1
                    PlaybackSourceResolverCache.invalidate(musicId)
                    safeEaseError(
                        "PLAYBACK_ROUTE_RETRY source=$sourceTag music=${musicId.value} code=${error.responseCode} attempt=$attempt"
                    )
                    continue
                }
                if (error.responseCode == 404) {
                    PlaybackSourceResolverCache.invalidate(musicId)
                    throw FileNotFoundException("music ${musicId.value} not found").apply {
                        initCause(error)
                    }
                }
                throw error
            } catch (error: Exception) {
                closeQuietly()
                throw error
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val delegate = currentDelegate ?: throw IllegalStateException("data source is not opened")
        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? {
        return currentDelegate?.uri ?: currentUri
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            currentDelegate?.close()
        } catch (error: Exception) {
            safeEaseError("music playback data source close failed: $error")
        }
        currentDelegate = null
        currentUri = null
    }

    private fun extractMusicId(uri: Uri): MusicId {
        return parsePlaybackMusicId(uri)
            ?: throw FileNotFoundException("unsupported playback uri: $uri")
    }

    internal fun currentDelegateForTest(): DataSource? {
        return currentDelegate
    }
}

fun buildMusicPlaybackDataSourceFactory(
    appContext: Context,
    bridge: Bridge,
    downloadRepository: DownloadRepository,
    scope: CoroutineScope,
    sourceTag: String = PLAYBACK_SOURCE_TAG_PLAYBACK,
): DataSource.Factory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(PLAYBACK_HTTP_USER_AGENT)

    val resolver = MusicPlaybackSourceResolver { musicId: MusicId ->
        try {
            resolveMusicPlaybackSourceWithBridge(bridge, musicId, downloadRepository)
        } catch (error: CancellationException) {
            throw IOException("resolve playback source cancelled for music=${musicId.value}", error)
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("resolve playback source failed for music=${musicId.value}", error)
        }
    }

    return DataSource.Factory {
        MusicPlaybackDataSource(
            resolver = resolver,
            httpDataSourceFactory = httpFactory,
            fileDataSourceFactory = DataSource.Factory { FileDataSource() },
            contentDataSourceFactory = DataSource.Factory { ContentDataSource(appContext) },
            streamFallbackFactory = DataSource.Factory { MusicPlayerDataSource(bridge, scope) },
            sourceTag = sourceTag,
        )
    }
}

private fun sanitizePlaybackRouteUriForLog(uri: Uri): String {
    return when (uri.scheme) {
        "http", "https" -> buildString {
            append(uri.scheme)
            append("://")
            append(uri.authority ?: "")
            append(uri.path ?: "")
        }
        else -> uri.toString()
    }
}

private fun PlaybackSourceDescriptor.toResolvedMusicPlaybackSource(): ResolvedMusicPlaybackSource {
    return when (this) {
        is PlaybackSourceDescriptor.DirectHttp -> ResolvedMusicPlaybackSource.DirectHttp(
            url = v1.url,
            headers = v1.headers.map { header ->
                MusicPlaybackHttpHeader(
                    name = header.name,
                    value = header.value,
                )
            },
            cacheKey = v1.cacheKey,
        )
        is PlaybackSourceDescriptor.LocalFile -> ResolvedMusicPlaybackSource.LocalFile(
            absolutePath = v1.absolutePath,
        )
        PlaybackSourceDescriptor.StreamFallback -> ResolvedMusicPlaybackSource.StreamFallback
    }
}

private fun safeEaseLog(message: String) {
    try {
        easeLog(message)
    } catch (_: Throwable) {
        // JVM unit tests do not load the UniFFI library; logging must stay best-effort.
    }
}

private fun safeEaseError(message: String) {
    try {
        easeError(message)
    } catch (_: Throwable) {
        // JVM unit tests do not load the UniFFI library; logging must stay best-effort.
    }
}
