package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.C.LENGTH_UNSET
import androidx.media3.common.C.RESULT_END_OF_INPUT
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.kutedev.easemusicplayer.singleton.Bridge
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import uniffi.ease_client_backend.AssetStream
import uniffi.ease_client_backend.ctGetAssetStream
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_schema.MusicId

class MusicPlayerDataSource(
    private val bridge: Bridge,
    private val scope: CoroutineScope
) : BaseDataSource(true) {
    companion object {
        private const val MAX_STREAM_RETRIES = 2
    }

    private var currentUri: Uri? = null
    private var currentMusicId: MusicId? = null
    private var currentStream: AssetStream? = null
    private var currentChunk: ByteArray? = null
    private var currentChunkOffset = 0
    private var currentPosition = 0L
    private var bytesRemaining = LENGTH_UNSET.toLong()
    private var transferOpened = false

    override fun open(dataSpec: DataSpec): Long {
        reset(endTransfer = false)
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val musicId = parsePlaybackMusicId(dataSpec.uri)
                ?: throw FileNotFoundException("music uri ${dataSpec.uri} not found")
            currentMusicId = musicId
            currentPosition = dataSpec.position

            val assetStream = openAssetStream(musicId, dataSpec.position)
            val remainingFromSource = assetStream.size()?.toLong()
            if (remainingFromSource != null && remainingFromSource <= 0L) {
                throw EOFException("music ${musicId.value} has no remaining bytes at position=${dataSpec.position}")
            }

            currentStream = assetStream
            bytesRemaining = when {
                dataSpec.length == LENGTH_UNSET.toLong() && remainingFromSource != null -> remainingFromSource
                dataSpec.length == LENGTH_UNSET.toLong() -> LENGTH_UNSET.toLong()
                remainingFromSource != null -> min(dataSpec.length, remainingFromSource)
                else -> dataSpec.length
            }

            transferStarted(dataSpec)
            transferOpened = true
            return bytesRemaining
        } catch (error: IOException) {
            reset(endTransfer = false)
            throw error
        } catch (error: RuntimeException) {
            reset(endTransfer = false)
            throw error
        }
    }

    override fun getUri(): Uri? {
        return currentUri
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return RESULT_END_OF_INPUT
        }

        var chunk = currentChunk
        if (chunk == null || currentChunkOffset >= chunk.size) {
            chunk = readNextChunk() ?: return RESULT_END_OF_INPUT
            currentChunk = chunk
            currentChunkOffset = 0
        }

        val availableInChunk = chunk.size - currentChunkOffset
        val bytesToRead = if (bytesRemaining == LENGTH_UNSET.toLong()) {
            min(length, availableInChunk)
        } else {
            min(length.toLong(), min(bytesRemaining, availableInChunk.toLong())).toInt()
        }
        if (bytesToRead <= 0) {
            return RESULT_END_OF_INPUT
        }

        System.arraycopy(chunk, currentChunkOffset, buffer, offset, bytesToRead)
        currentChunkOffset += bytesToRead
        if (currentChunkOffset >= chunk.size) {
            currentChunk = null
            currentChunkOffset = 0
        }

        currentPosition += bytesToRead
        if (bytesRemaining != LENGTH_UNSET.toLong()) {
            bytesRemaining = (bytesRemaining - bytesToRead).coerceAtLeast(0L)
        }
        bytesTransferred(bytesToRead)
        return bytesToRead
    }

    override fun close() {
        reset(endTransfer = transferOpened)
    }

    private fun readNextChunk(): ByteArray? {
        val musicId = currentMusicId ?: return null
        var retries = 0

        while (true) {
            val stream = currentStream ?: openAssetStream(musicId, currentPosition).also { currentStream = it }
            try {
                val bytes = runBlocking { stream.next() }
                if (bytes != null) {
                    return bytes
                }
                if (bytesRemaining == LENGTH_UNSET.toLong() || bytesRemaining == 0L) {
                    return null
                }
                if (retries >= MAX_STREAM_RETRIES) {
                    throw EOFException("stream ended early at position=$currentPosition")
                }
                retries += 1
                easeError("stream ended early, retry $retries at position=$currentPosition")
                currentStream = openAssetStream(musicId, currentPosition)
            } catch (error: CancellationException) {
                throw IOException("stream read cancelled at position=$currentPosition", error)
            } catch (error: FileNotFoundException) {
                throw error
            } catch (error: EOFException) {
                throw error
            } catch (error: Exception) {
                if (retries >= MAX_STREAM_RETRIES) {
                    throw IOException("stream read failed after retries at position=$currentPosition", error)
                }
                retries += 1
                easeError("load chunk failed, retry $retries at position=$currentPosition: $error")
                currentStream = openAssetStream(musicId, currentPosition)
            }
        }
    }

    private fun openAssetStream(musicId: MusicId, position: Long): AssetStream {
        return try {
            runBlocking {
                bridge.runRaw { ctGetAssetStream(it, DataSourceKey.Music(musicId), position.toULong()) }
            } ?: throw FileNotFoundException("music ${musicId.value} not found at position=$position")
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: Exception) {
            throw IOException("open asset stream failed at position=$position", error)
        }
    }

    private fun reset(endTransfer: Boolean) {
        currentStream = null
        currentChunk = null
        currentChunkOffset = 0
        currentPosition = 0L
        bytesRemaining = LENGTH_UNSET.toLong()
        currentMusicId = null
        currentUri = null
        if (endTransfer && transferOpened) {
            transferEnded()
        }
        transferOpened = false
    }
}
