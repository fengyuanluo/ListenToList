package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
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
