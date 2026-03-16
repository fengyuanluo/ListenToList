package com.kutedev.easemusicplayer.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadWorkKeys
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.AssetStream
import uniffi.ease_client_backend.ctGetAssetStream
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId
import kotlin.coroutines.coroutineContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerEntryPoint {
    fun bridge(): Bridge
}

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val MAX_STREAM_RETRIES = 2
        private const val PROGRESS_STEP_BYTES = 128 * 1024L
    }

    private val bridge: Bridge by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadWorkerEntryPoint::class.java,
        ).bridge()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val storageId = inputData.getLong(DownloadWorkKeys.STORAGE_ID, -1L)
        val sourcePath = inputData.getString(DownloadWorkKeys.SOURCE_PATH)
        val targetPath = inputData.getString(DownloadWorkKeys.TARGET_PATH)
        val sizeHint = inputData.getLong(DownloadWorkKeys.SIZE_BYTES, -1L).takeIf { it >= 0L }
        if (storageId < 0L || sourcePath.isNullOrBlank() || targetPath.isNullOrBlank()) {
            return@withContext failure("下载任务参数不完整")
        }

        bridge.initialize()

        val destinationFile = File(targetPath)
        val tempFile = File("${targetPath}.part")
        destinationFile.parentFile?.mkdirs()
        tempFile.parentFile?.mkdirs()
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val sourceKey = DataSourceKey.AnyEntry(
            StorageEntryLoc(
                storageId = StorageId(storageId),
                path = sourcePath,
            )
        )

        return@withContext try {
            val result = copyAssetToFile(
                sourceKey = sourceKey,
                targetFile = tempFile,
                sizeHint = sizeHint,
            )
            ensureActive()
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
            Result.success(
                workDataOf(
                    DownloadWorkKeys.OUTPUT_DEST_PATH to destinationFile.absolutePath,
                    DownloadWorkKeys.OUTPUT_DOWNLOADED_BYTES to result.downloadedBytes,
                )
            )
        } catch (error: Throwable) {
            tempFile.delete()
            if (error is kotlinx.coroutines.CancellationException) {
                throw error
            }
            failure(error.message ?: "下载失败")
        }
    }

    private suspend fun copyAssetToFile(
        sourceKey: DataSourceKey,
        targetFile: File,
        sizeHint: Long?,
    ): DownloadCopyResult {
        var downloadedBytes = 0L
        var stream = openAssetStream(sourceKey, offset = 0L)
        var totalBytes = sizeHint ?: stream.size()?.toLong()
        var lastProgressBytes = 0L

        targetFile.outputStream().buffered().use { output ->
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val nextChunk = readNextChunk(
                        sourceKey = sourceKey,
                        currentStream = stream,
                        currentOffset = downloadedBytes,
                    )
                    stream = nextChunk.stream
                    val chunk = nextChunk.chunk ?: break
                    output.write(chunk)
                    downloadedBytes += chunk.size
                    if (totalBytes == null) {
                        totalBytes = nextChunk.totalBytes
                    }
                    if (
                        downloadedBytes == chunk.size.toLong() ||
                        downloadedBytes - lastProgressBytes >= PROGRESS_STEP_BYTES ||
                        (totalBytes != null && downloadedBytes >= totalBytes)
                    ) {
                        setProgress(
                            workDataOf(
                                DownloadWorkKeys.PROGRESS_BYTES to downloadedBytes,
                                DownloadWorkKeys.PROGRESS_TOTAL_BYTES to (totalBytes ?: -1L),
                            )
                        )
                        lastProgressBytes = downloadedBytes
                    }
                }
            } finally {
                runCatching { stream.close() }
            }
        }

        return DownloadCopyResult(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes ?: downloadedBytes,
        )
    }

    private suspend fun readNextChunk(
        sourceKey: DataSourceKey,
        currentStream: AssetStream,
        currentOffset: Long,
    ): DownloadChunkResult {
        var stream = currentStream
        var retries = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val bytes = stream.next()
                if (bytes != null) {
                    return DownloadChunkResult(
                        stream = stream,
                        chunk = bytes,
                        totalBytes = stream.size()?.toLong(),
                    )
                }
                return DownloadChunkResult(
                    stream = stream,
                    chunk = null,
                    totalBytes = stream.size()?.toLong()?.let { size -> currentOffset + size },
                )
            } catch (error: FileNotFoundException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: EOFException) {
                if (retries >= MAX_STREAM_RETRIES) {
                    throw error
                }
                retries += 1
                runCatching { stream.close() }
                stream = openAssetStream(sourceKey, currentOffset)
            } catch (error: Exception) {
                if (retries >= MAX_STREAM_RETRIES) {
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: "未知原因"
                    throw IOException("下载数据流读取失败：$detail", error)
                }
                retries += 1
                runCatching { stream.close() }
                stream = openAssetStream(sourceKey, currentOffset)
            }
        }
    }

    private suspend fun openAssetStream(
        sourceKey: DataSourceKey,
        offset: Long,
    ): AssetStream {
        return try {
            bridge.runRaw { backend ->
                ctGetAssetStream(backend, sourceKey, offset.toULong())
            } ?: throw FileNotFoundException("目标文件不存在")
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            throw IOException("打开下载数据流失败：$detail", error)
        }
    }

    private fun failure(message: String): Result {
        return Result.failure(
            workDataOf(
                DownloadWorkKeys.OUTPUT_ERROR_MESSAGE to message,
            )
        )
    }
}

private data class DownloadChunkResult(
    val stream: AssetStream,
    val chunk: ByteArray?,
    val totalBytes: Long?,
)

private data class DownloadCopyResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
)
