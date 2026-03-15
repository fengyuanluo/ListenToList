package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.kutedev.easemusicplayer.singleton.Bridge
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import uniffi.ease_client_backend.PlaybackSourceDescriptor
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

    data object StreamFallback : ResolvedMusicPlaybackSource()
}

fun interface MusicPlaybackSourceResolver {
    @Throws(IOException::class)
    fun resolve(musicId: MusicId): ResolvedMusicPlaybackSource?
}

class MusicPlaybackDataSource(
    private val resolver: MusicPlaybackSourceResolver,
    private val httpDataSourceFactory: DataSource.Factory,
    private val fileDataSourceFactory: DataSource.Factory,
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

        var attempt = 0
        while (true) {
            val resolved = resolver.resolve(musicId)
                ?: throw FileNotFoundException("music ${musicId.value} not found")

            val route = when (resolved) {
                is ResolvedMusicPlaybackSource.DirectHttp -> PLAYBACK_ROUTE_DIRECT_HTTP
                is ResolvedMusicPlaybackSource.LocalFile -> PLAYBACK_ROUTE_LOCAL_FILE
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
                    resolved.cacheKey?.let { builder.setKey(dataSpec.key ?: it) }
                    httpDataSourceFactory.createDataSource() to builder.build()
                }
                is ResolvedMusicPlaybackSource.LocalFile -> {
                    val spec = dataSpec.buildUpon()
                        .setUri(Uri.fromFile(File(resolved.absolutePath)))
                        .setKey(dataSpec.key ?: resolved.absolutePath)
                        .build()
                    fileDataSourceFactory.createDataSource() to spec
                }
                ResolvedMusicPlaybackSource.StreamFallback -> {
                    streamFallbackFactory.createDataSource() to dataSpec
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
                if (error.responseCode == 404) {
                    throw FileNotFoundException("music ${musicId.value} not found").apply {
                        initCause(error)
                    }
                }
                if (
                    resolved is ResolvedMusicPlaybackSource.DirectHttp &&
                    (error.responseCode == 401 || error.responseCode == 403) &&
                    attempt < MAX_DIRECT_HTTP_OPEN_RETRIES
                ) {
                    attempt += 1
                    safeEaseError(
                        "PLAYBACK_ROUTE_RETRY source=$sourceTag music=${musicId.value} code=${error.responseCode} attempt=$attempt"
                    )
                    continue
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
    bridge: Bridge,
    scope: CoroutineScope,
    sourceTag: String = PLAYBACK_SOURCE_TAG_PLAYBACK,
): DataSource.Factory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(PLAYBACK_HTTP_USER_AGENT)

    val resolver = MusicPlaybackSourceResolver { musicId: MusicId ->
        try {
            runBlocking {
                bridge.runRaw { backend ->
                    ctResolveMusicPlaybackSource(backend, musicId)
                        ?.toResolvedMusicPlaybackSource()
                }
            }
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
