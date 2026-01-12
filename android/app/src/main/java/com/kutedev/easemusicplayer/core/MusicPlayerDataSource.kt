package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.C.LENGTH_UNSET
import androidx.media3.common.C.RESULT_END_OF_INPUT
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.kutedev.easemusicplayer.singleton.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uniffi.ease_client_backend.AssetStream
import uniffi.ease_client_backend.ctGetAssetStream
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_schema.MusicId
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.min

class MusicPlayerDataSource(
    private val bridge: Bridge,
    private val scope: CoroutineScope
) : DataSource {
    companion object {
        private const val MAX_STREAM_RETRIES = 2
    }

    private var _currentUri: Uri? = null
    private var _inputStream: InputStream? = null
    private var _outputStream: PipedOutputStream? = null
    private var _loadJob: Job? = null
    @Volatile private var _isClosed = true
    @Volatile private var _remainingBytes: Long = LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        // noop
    }

    override fun open(dataSpec: DataSpec): Long {
        reset()
        _isClosed = false
        _remainingBytes = dataSpec.length

        val raw = dataSpec.uri.getQueryParameter("music")
        val musicId = raw?.toLongOrNull()?.let { MusicId(it) }

        if (musicId == null) {
            easeError("music id $raw not found, skip data source")
            return openEmpty(dataSpec)
        }

        _currentUri = dataSpec.uri

        val assetStream = runBlocking {
            bridge.run { ctGetAssetStream(it, DataSourceKey.Music(musicId), dataSpec.position.toULong()) }
        }
        if (assetStream == null) {
            easeError("music $raw not found, skip data source")
            return openEmpty(dataSpec)
        }

        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        _inputStream = input
        _outputStream = output

        val remainingBytes = assetStream.size()?.toLong()
        if (remainingBytes != null && remainingBytes <= 0L) {
            easeError("music $raw remaining bytes is 0, skip data source")
            return openEmpty(dataSpec)
        }
        if (_remainingBytes == LENGTH_UNSET.toLong()) {
            _remainingBytes = remainingBytes ?: LENGTH_UNSET.toLong()
        } else if (remainingBytes != null) {
            _remainingBytes = min(_remainingBytes, remainingBytes)
        }

        val expectedBytes = if (_remainingBytes != LENGTH_UNSET.toLong()) {
            _remainingBytes
        } else {
            null
        }
        _loadJob = scope.launch(Dispatchers.IO) {
            writeStream(musicId, dataSpec.position, assetStream, output, expectedBytes)
        }

        return if (_remainingBytes != LENGTH_UNSET.toLong()) {
            _remainingBytes
        } else {
            LENGTH_UNSET.toLong()
        }
    }

    override fun getUri(): Uri? {
        return _currentUri
    }

    override fun close() {
        reset()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        val stream = _inputStream ?: return RESULT_END_OF_INPUT
        if (_remainingBytes == 0L) {
            return RESULT_END_OF_INPUT
        }
        val targetLength = if (_remainingBytes != LENGTH_UNSET.toLong()) {
            min(length.toLong(), _remainingBytes).toInt()
        } else {
            length
        }
        if (targetLength <= 0) {
            return RESULT_END_OF_INPUT
        }
        val read = try {
            stream.read(buffer, offset, targetLength)
        } catch (e: IOException) {
            if (_isClosed) {
                return RESULT_END_OF_INPUT
            }
            easeError("read stream failed: $e")
            _remainingBytes = 0
            return RESULT_END_OF_INPUT
        }
        if (read == -1) {
            _remainingBytes = 0
            return RESULT_END_OF_INPUT
        }
        if (_remainingBytes != LENGTH_UNSET.toLong()) {
            _remainingBytes = (_remainingBytes - read).coerceAtLeast(0L)
        }
        return read
    }

    private fun reset() {
        _isClosed = true
        _remainingBytes = LENGTH_UNSET.toLong()
        _loadJob?.cancel()
        _loadJob = null
        _currentUri = null
        _outputStream?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        _outputStream = null
        _inputStream?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        _inputStream = null
    }

    private fun openEmpty(dataSpec: DataSpec): Long {
        _currentUri = dataSpec.uri
        _remainingBytes = 0
        _inputStream = ByteArrayInputStream(ByteArray(0))
        _outputStream = null
        _loadJob = null
        return 0L
    }

    private suspend fun writeStream(
        musicId: MusicId,
        initialOffset: Long,
        initialStream: AssetStream,
        output: PipedOutputStream,
        expectedBytes: Long?
    ) {
        var stream: AssetStream? = initialStream
        var offset = initialOffset
        val expectedEnd = expectedBytes?.let { initialOffset + it }
        var retries = 0

        try {
            while (currentCoroutineContext().isActive) {
                val current = stream ?: break
                try {
                    val b = current.next()
                    if (b == null) {
                        if (expectedEnd != null && offset < expectedEnd) {
                            if (retries >= MAX_STREAM_RETRIES) {
                                easeError("load chunk ended early at offset=$offset")
                                break
                            }
                            retries += 1
                            easeError("stream ended early, retry $retries at offset=$offset")
                            stream = bridge.run {
                                ctGetAssetStream(it, DataSourceKey.Music(musicId), offset.toULong())
                            }
                            if (stream == null) {
                                easeError("reopen asset stream failed at offset=$offset")
                                break
                            }
                            continue
                        }
                        break
                    }
                    output.write(b)
                    offset += b.size
                    retries = 0
                } catch (e: CancellationException) {
                    break
                } catch (e: IOException) {
                    break
                } catch (e: Exception) {
                    if (retries >= MAX_STREAM_RETRIES) {
                        easeError("load chunk failed after retries: $e")
                        break
                    }
                    retries += 1
                    easeError("load chunk failed, retry $retries: $e")
                    stream = bridge.run {
                        ctGetAssetStream(it, DataSourceKey.Music(musicId), offset.toULong())
                    }
                    if (stream == null) {
                        easeError("reopen asset stream failed at offset=$offset")
                        break
                    }
                }
            }
        } finally {
            try {
                output.close()
            } catch (_: IOException) {
            }
        }
    }
}
