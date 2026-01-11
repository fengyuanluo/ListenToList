package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C.LENGTH_UNSET
import androidx.media3.common.C.RESULT_END_OF_INPUT
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.kutedev.easemusicplayer.singleton.Bridge
import kotlinx.coroutines.CoroutineScope
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
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

@OptIn(UnstableApi::class)
class MusicPlayerDataSource(
    private val bridge: Bridge,
    private val scope: CoroutineScope
) : DataSource {
    companion object {
        private const val MAX_STREAM_RETRIES = 2
    }

    private var _currentUri: Uri? = null
    private var _inputStream: PipedInputStream? = null
    private var _outputStream: PipedOutputStream? = null
    private var _loadJob: Job? = null

    override fun addTransferListener(transferListener: TransferListener) {
        // noop
    }

    override fun open(dataSpec: DataSpec): Long {
        reset()

        val raw = dataSpec.uri.getQueryParameter("music")
        val musicId = raw?.toLong()?.let { MusicId(it) }

        if (musicId == null) {
            throw RuntimeException("music id $raw not found")
        }

        _currentUri = dataSpec.uri

        val assetStream = runBlocking {
            bridge.run { ctGetAssetStream(it, DataSourceKey.Music(musicId), dataSpec.position.toULong()) }
        }
        if (assetStream == null) {
            throw RuntimeException("music $raw not found")
        }

        val input = PipedInputStream()
        val output = PipedOutputStream(input)
        _inputStream = input
        _outputStream = output

        _loadJob = scope.launch(Dispatchers.IO) {
            writeStream(musicId, dataSpec.position, assetStream, output)
        }

        val fileSize = assetStream.size()
        if (fileSize != null) {
            return fileSize.toLong()
        }
        return LENGTH_UNSET.toLong()
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
        val read = stream.read(buffer, offset, length)
        if (read == -1) {
            return RESULT_END_OF_INPUT
        }
        return read
    }

    private fun reset() {
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

    private suspend fun writeStream(
        musicId: MusicId,
        initialOffset: Long,
        initialStream: AssetStream,
        output: PipedOutputStream
    ) {
        var stream: AssetStream? = initialStream
        var offset = initialOffset
        var retries = 0

        try {
            while (currentCoroutineContext().isActive) {
                val current = stream ?: break
                try {
                    val b = current.next()
                    if (b == null) {
                        break
                    }
                    output.write(b)
                    offset += b.size
                    retries = 0
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
