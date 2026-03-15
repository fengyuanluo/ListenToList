package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicPlaybackDataSourceTest {
    @Before
    fun resetDiagnostics() {
        PlaybackDiagnostics.reset()
        PlaybackSourceResolverCache.resetForTest()
    }

    @Test
    fun directHttpDescriptor_usesHttpDelegateAndResolvedSpec() {
        val http = RecordingDataSource()
        val file = RecordingDataSource()
        val fallback = RecordingDataSource()
        val dataSource = MusicPlaybackDataSource(
            resolver = MusicPlaybackSourceResolver {
                ResolvedMusicPlaybackSource.DirectHttp(
                    url = "https://example.com/music/sample.wav",
                    headers = listOf(
                        MusicPlaybackHttpHeader("Authorization", "Bearer test-token")
                    ),
                    cacheKey = "music-cache-key",
                )
            },
            httpDataSourceFactory = DataSource.Factory { http },
            fileDataSourceFactory = DataSource.Factory { file },
            streamFallbackFactory = DataSource.Factory { fallback },
        )

        val opened = dataSource.open(
            DataSpec.Builder()
                .setUri(Uri.parse("ease://data?music=7"))
                .setHttpRequestHeaders(mapOf("X-Test" to "1"))
                .build()
        )

        assertEquals(4L, opened)
        assertSame(http, dataSource.currentDelegateForTest())
        assertEquals(
            Uri.parse("https://example.com/music/sample.wav"),
            http.openedSpec?.uri,
        )
        assertEquals("music-cache-key", http.openedSpec?.key)
        assertEquals("1", http.openedSpec?.httpRequestHeaders?.get("X-Test"))
        assertEquals(
            "Bearer test-token",
            http.openedSpec?.httpRequestHeaders?.get("Authorization"),
        )
        assertEquals(PLAYBACK_ROUTE_DIRECT_HTTP, PlaybackDiagnostics.currentSnapshot().route)
    }

    @Test
    fun localFileDescriptor_usesFileDelegateAndResolvedSpec() {
        val http = RecordingDataSource()
        val file = RecordingDataSource()
        val fallback = RecordingDataSource()
        val path = File("/tmp/listentolist-test.wav").absolutePath
        val dataSource = MusicPlaybackDataSource(
            resolver = MusicPlaybackSourceResolver {
                ResolvedMusicPlaybackSource.LocalFile(absolutePath = path)
            },
            httpDataSourceFactory = DataSource.Factory { http },
            fileDataSourceFactory = DataSource.Factory { file },
            streamFallbackFactory = DataSource.Factory { fallback },
        )

        dataSource.open(DataSpec.Builder().setUri(Uri.parse("ease://data?music=8")).build())

        assertSame(file, dataSource.currentDelegateForTest())
        assertEquals(Uri.fromFile(File(path)), file.openedSpec?.uri)
        assertEquals(path, file.openedSpec?.key)
        assertEquals(PLAYBACK_ROUTE_LOCAL_FILE, PlaybackDiagnostics.currentSnapshot().route)
    }

    @Test
    fun streamFallbackDescriptor_usesFallbackDelegateAndReadDelegates() {
        val http = RecordingDataSource()
        val file = RecordingDataSource()
        val fallback = RecordingDataSource(bytes = "data".encodeToByteArray())
        val dataSource = MusicPlaybackDataSource(
            resolver = MusicPlaybackSourceResolver {
                ResolvedMusicPlaybackSource.StreamFallback
            },
            httpDataSourceFactory = DataSource.Factory { http },
            fileDataSourceFactory = DataSource.Factory { file },
            streamFallbackFactory = DataSource.Factory { fallback },
        )

        dataSource.open(DataSpec.Builder().setUri(Uri.parse("ease://data?music=9")).build())

        val buffer = ByteArray(8)
        val read = dataSource.read(buffer, 0, buffer.size)
        assertEquals(4, read)
        assertSame(fallback, dataSource.currentDelegateForTest())
        assertEquals("data", String(buffer, 0, read))
        assertEquals(PLAYBACK_ROUTE_STREAM_FALLBACK, PlaybackDiagnostics.currentSnapshot().route)
    }

    @Test
    fun resolverCache_reusesDescriptorAcrossDataSourceInstances() {
        var resolveCalls = 0
        val resolver = MusicPlaybackSourceResolver {
            resolveCalls += 1
            ResolvedMusicPlaybackSource.DirectHttp(
                url = "https://example.com/music/cache.wav",
                headers = emptyList(),
                cacheKey = "music-cache-key",
            )
        }

        fun newDataSource(): MusicPlaybackDataSource {
            return MusicPlaybackDataSource(
                resolver = MusicPlaybackSourceResolver { musicId ->
                    PlaybackSourceResolverCache.resolve(musicId) {
                        resolver.resolve(musicId)
                    }
                },
                httpDataSourceFactory = DataSource.Factory { RecordingDataSource() },
                fileDataSourceFactory = DataSource.Factory { RecordingDataSource() },
                streamFallbackFactory = DataSource.Factory { RecordingDataSource() },
            )
        }

        val first = newDataSource()
        first.open(DataSpec.Builder().setUri(Uri.parse("ease://data?music=42")).build())
        first.close()

        val second = newDataSource()
        second.open(DataSpec.Builder().setUri(Uri.parse("ease://data?music=42")).build())
        second.close()

        assertEquals(1, resolveCalls)
    }

    @Test
    fun directHttp404_invalidatesCacheAndRetriesResolve() {
        var resolveCalls = 0
        val http = FlakyHttpDataSource()
        val dataSource = MusicPlaybackDataSource(
            resolver = MusicPlaybackSourceResolver { musicId ->
                PlaybackSourceResolverCache.resolve(musicId) {
                    resolveCalls += 1
                    ResolvedMusicPlaybackSource.DirectHttp(
                        url = "https://example.com/music/${resolveCalls}.wav",
                        headers = emptyList(),
                        cacheKey = "music-cache-key-$resolveCalls",
                    )
                }
            },
            httpDataSourceFactory = DataSource.Factory { http },
            fileDataSourceFactory = DataSource.Factory { RecordingDataSource() },
            streamFallbackFactory = DataSource.Factory { RecordingDataSource() },
        )

        val opened = dataSource.open(
            DataSpec.Builder()
                .setUri(Uri.parse("ease://data?music=43"))
                .build()
        )

        assertEquals(4L, opened)
        assertEquals(2, resolveCalls)
        assertEquals(Uri.parse("https://example.com/music/2.wav"), http.openedSpec?.uri)
    }
}

private class RecordingDataSource(
    private val openResult: Long = 4L,
    private val bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
) : DataSource {
    var openedSpec: DataSpec? = null
        private set

    private var readOffset = 0

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        openedSpec = dataSpec
        readOffset = 0
        return openResult
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readOffset >= bytes.size) {
            return C.RESULT_END_OF_INPUT
        }
        val toCopy = min(length, bytes.size - readOffset)
        System.arraycopy(bytes, readOffset, buffer, offset, toCopy)
        readOffset += toCopy
        return toCopy
    }

    override fun getUri(): Uri? {
        return openedSpec?.uri
    }

    override fun close() = Unit
}

private class FlakyHttpDataSource : DataSource {
    var openedSpec: DataSpec? = null
        private set
    private var openAttempts = 0

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        openAttempts += 1
        if (openAttempts == 1) {
            throw HttpDataSource.InvalidResponseCodeException(
                404,
                "Not Found",
                IOException("expired direct url"),
                Collections.emptyMap(),
                dataSpec,
                ByteArray(0),
            )
        }
        openedSpec = dataSpec
        return 4L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = openedSpec?.uri

    override fun close() = Unit
}
