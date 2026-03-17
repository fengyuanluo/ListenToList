package com.kutedev.easemusicplayer.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.AssetStream
import uniffi.ease_client_backend.ctGetAssetStream
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerEntryPoint {
    fun bridge(): Bridge
}

private sealed class DownloadWorkerTarget {
    data class FilePath(
        val destinationPath: String,
        val tempPath: String,
    ) : DownloadWorkerTarget()

    data class ContentTree(
        val fileName: String,
        val treeUri: String,
        val destinationUri: String?,
        val tempUri: String,
    ) : DownloadWorkerTarget()
}

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "DownloadWorker"
        private const val MAX_STREAM_RETRIES = 2
        private const val PROGRESS_STEP_BYTES = 128 * 1024L
        private const val OCTET_STREAM_MIME = "application/octet-stream"
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
        val sizeHint = inputData.getLong(DownloadWorkKeys.SIZE_BYTES, -1L).takeIf { it >= 0L }
        val target = parseTarget()
        if (storageId < 0L || sourcePath.isNullOrBlank() || target == null) {
            return@withContext failure("下载任务参数不完整")
        }

        bridge.initialize()

        val sourceKey = DataSourceKey.AnyEntry(
            StorageEntryLoc(
                storageId = StorageId(storageId),
                path = sourcePath,
            )
        )

        return@withContext try {
            val existingBytes = existingDownloadedBytes(target)
            val result = copyAssetToTarget(
                sourceKey = sourceKey,
                target = target,
                sizeHint = sizeHint,
                existingBytes = existingBytes,
            )
            ensureActive()
            val finalized = finalizeTarget(target)
            Result.success(
                workDataOf(
                    DownloadWorkKeys.OUTPUT_DEST_PATH to finalized.destinationPath,
                    DownloadWorkKeys.OUTPUT_DEST_URI to finalized.destinationUri,
                    DownloadWorkKeys.OUTPUT_DOWNLOADED_BYTES to result.downloadedBytes,
                )
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            Log.e(
                TAG,
                "download failed storageId=$storageId sourcePath=$sourcePath target=$target error=${error::class.java.simpleName}: ${error.message}",
                error,
            )
            failure(error.message ?: error.toString())
        }
    }

    private suspend fun copyAssetToTarget(
        sourceKey: DataSourceKey,
        target: DownloadWorkerTarget,
        sizeHint: Long?,
        existingBytes: Long,
    ): DownloadCopyResult {
        var downloadedBytes = existingBytes.coerceAtLeast(0L)
        var stream = openAssetStream(sourceKey, offset = downloadedBytes)
        var totalBytes = sizeHint ?: stream.size()?.toLong()?.let { downloadedBytes + it }
        var lastProgressBytes = downloadedBytes

        if (downloadedBytes > 0L) {
            setProgress(
                workDataOf(
                    DownloadWorkKeys.PROGRESS_BYTES to downloadedBytes,
                    DownloadWorkKeys.PROGRESS_TOTAL_BYTES to (totalBytes ?: -1L),
                )
            )
        }
        if (totalBytes != null && downloadedBytes >= totalBytes) {
            return DownloadCopyResult(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )
        }

        openTargetOutput(target, append = downloadedBytes > 0L).use { output ->
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
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
                        downloadedBytes == existingBytes + chunk.size.toLong() ||
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
            currentCoroutineContext().ensureActive()
            try {
                val bytes = stream.next()
                val totalBytes = stream.size()?.toLong()?.let { currentOffset + it }
                if (bytes != null) {
                    return DownloadChunkResult(
                        stream = stream,
                        chunk = bytes,
                        totalBytes = totalBytes,
                    )
                }
                return DownloadChunkResult(
                    stream = stream,
                    chunk = null,
                    totalBytes = totalBytes,
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

    private fun parseTarget(): DownloadWorkerTarget? {
        val fileName = inputData.getString(DownloadWorkKeys.FILE_NAME).orEmpty()
        val targetPath = inputData.getString(DownloadWorkKeys.TARGET_PATH)
        val targetUri = inputData.getString(DownloadWorkKeys.TARGET_URI)
        val tempPath = inputData.getString(DownloadWorkKeys.TEMP_TARGET_PATH)
        val tempUri = inputData.getString(DownloadWorkKeys.TEMP_TARGET_URI)
        val treeUri = inputData.getString(DownloadWorkKeys.DIRECTORY_TREE_URI)

        return if (!treeUri.isNullOrBlank() && !tempUri.isNullOrBlank() && fileName.isNotBlank()) {
            DownloadWorkerTarget.ContentTree(
                fileName = fileName,
                treeUri = treeUri,
                destinationUri = targetUri,
                tempUri = tempUri,
            )
        } else if (!targetPath.isNullOrBlank() && !tempPath.isNullOrBlank()) {
            DownloadWorkerTarget.FilePath(
                destinationPath = targetPath,
                tempPath = tempPath,
            )
        } else {
            null
        }
    }

    private fun existingDownloadedBytes(target: DownloadWorkerTarget): Long {
        return when (target) {
            is DownloadWorkerTarget.FilePath -> {
                val tempFile = File(target.tempPath)
                tempFile.parentFile?.mkdirs()
                if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
            }
            is DownloadWorkerTarget.ContentTree -> {
                documentLength(target.tempUri)
            }
        }
    }

    private fun openTargetOutput(target: DownloadWorkerTarget, append: Boolean): OutputStream {
        return when (target) {
            is DownloadWorkerTarget.FilePath -> {
                val tempFile = File(target.tempPath)
                tempFile.parentFile?.mkdirs()
                java.io.FileOutputStream(tempFile, append).buffered()
            }
            is DownloadWorkerTarget.ContentTree -> {
                val mode = if (append) "wa" else "w"
                applicationContext.contentResolver.openOutputStream(Uri.parse(target.tempUri), mode)
                    ?.buffered()
                    ?: throw IOException("无法写入下载文件")
            }
        }
    }

    private fun finalizeTarget(target: DownloadWorkerTarget): FinalizedTarget {
        return when (target) {
            is DownloadWorkerTarget.FilePath -> finalizeFileTarget(target)
            is DownloadWorkerTarget.ContentTree -> finalizeContentTarget(target)
        }
    }

    private fun finalizeFileTarget(target: DownloadWorkerTarget.FilePath): FinalizedTarget {
        val destinationFile = File(target.destinationPath)
        val tempFile = File(target.tempPath)
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        if (!tempFile.renameTo(destinationFile)) {
            tempFile.copyTo(destinationFile, overwrite = true)
            tempFile.delete()
        }
        return FinalizedTarget(destinationPath = destinationFile.absolutePath)
    }

    private fun finalizeContentTarget(target: DownloadWorkerTarget.ContentTree): FinalizedTarget {
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(target.treeUri))
            ?: throw IOException("下载目录不可用")
        val tempUri = Uri.parse(target.tempUri)
        val tempName = "${target.fileName}.part"
        val tempDocFromTree = tree.findFile(tempName)
        val tempDoc = tempDocFromTree
            ?: DocumentFile.fromSingleUri(applicationContext, tempUri)
            ?: throw IOException("临时下载文件不存在")

        val renameSucceeded = runCatching { tempDocFromTree?.renameTo(target.fileName) }
            .onFailure { error ->
                Log.w(TAG, "finalizeContentTarget rename failed: ${error.message}", error)
            }
            .getOrNull() == true
        if (renameSucceeded) {
            return FinalizedTarget(destinationUri = tempDocFromTree?.uri?.toString() ?: tempUri.toString())
        }

        val existingDestination = target.destinationUri
            ?.let { Uri.parse(it) }
            ?.let { uri -> DocumentFile.fromSingleUri(applicationContext, uri) }
            ?: tree.findFile(target.fileName)
        existingDestination?.delete()

        val finalDoc = tree.createFile(guessMimeType(target.fileName), target.fileName)
            ?: throw IOException("无法创建目标下载文件")
        copyUri(tempDoc.uri, finalDoc.uri)
        tempDoc.delete()
        return FinalizedTarget(destinationUri = finalDoc.uri.toString())
    }

    private fun documentLength(uriString: String): Long {
        val document = DocumentFile.fromSingleUri(applicationContext, Uri.parse(uriString))
        return document?.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
    }

    private fun copyUri(from: Uri, to: Uri) {
        val input = applicationContext.contentResolver.openInputStream(from)
            ?: throw IOException("无法读取临时下载文件")
        val output = applicationContext.contentResolver.openOutputStream(to, "w")
            ?: throw IOException("无法写入目标下载文件")
        input.use { source ->
            output.use { sink ->
                source.copyTo(sink)
            }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() }
        return extension?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: OCTET_STREAM_MIME
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

private data class FinalizedTarget(
    val destinationPath: String = "",
    val destinationUri: String? = null,
)
