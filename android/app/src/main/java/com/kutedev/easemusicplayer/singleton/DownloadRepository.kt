package com.kutedev.easemusicplayer.singleton

import android.content.Context
import android.os.Environment
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kutedev.easemusicplayer.core.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.StorageEntry

const val DOWNLOAD_WORK_TAG = "music-download-task"

internal object DownloadWorkKeys {
    const val STORAGE_ID = "storage_id"
    const val SOURCE_PATH = "source_path"
    const val TITLE = "title"
    const val FILE_NAME = "file_name"
    const val TARGET_PATH = "target_path"
    const val SIZE_BYTES = "size_bytes"
    const val CREATED_AT_MS = "created_at_ms"

    const val PROGRESS_BYTES = "progress_bytes"
    const val PROGRESS_TOTAL_BYTES = "progress_total_bytes"

    const val OUTPUT_DEST_PATH = "output_dest_path"
    const val OUTPUT_DOWNLOADED_BYTES = "output_downloaded_bytes"
    const val OUTPUT_ERROR_MESSAGE = "output_error_message"
}

enum class DownloadTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
    ;

    val active: Boolean
        get() = this == QUEUED || this == RUNNING || this == BLOCKED
}

data class DownloadTaskItem(
    val id: UUID,
    val title: String,
    val sourcePath: String,
    val fileName: String,
    val storageId: Long,
    val createdAtMs: Long,
    val status: DownloadTaskStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val destinationPath: String,
    val errorMessage: String? = null,
) {
    val active: Boolean
        get() = status == DownloadTaskStatus.QUEUED ||
            status == DownloadTaskStatus.RUNNING ||
            status == DownloadTaskStatus.BLOCKED

    val retryable: Boolean
        get() = status == DownloadTaskStatus.FAILED || status == DownloadTaskStatus.CANCELLED
}

@Serializable
private data class PersistedDownloadRecord(
    val id: String,
    val title: String,
    val sourcePath: String,
    val fileName: String,
    val storageId: Long,
    val createdAtMs: Long,
    val status: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val destinationPath: String,
    val errorMessage: String? = null,
)

private data class BuiltDownloadRequest(
    val request: OneTimeWorkRequest,
    val record: PersistedDownloadRecord,
)

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val toastRepository: ToastRepository,
) {
    companion object {
        private const val PREFS_NAME = "download_tasks"
        private const val KEY_TASKS_JSON = "tasks_json"
    }

    private val workManager = WorkManager.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val records = MutableStateFlow(loadPersisted())

    val tasks: Flow<List<DownloadTaskItem>> = callbackFlow {
        val liveData = workManager.getWorkInfosByTagLiveData(DOWNLOAD_WORK_TAG)
        val observer = Observer<List<WorkInfo>> { infos ->
            trySend(
                mergeWithWorkInfos(infos.orEmpty())
                    .sortedByDescending { item -> item.createdAtMs }
            )
        }
        liveData.observeForever(observer)
        trySend(records.value.map(::toTaskItem).sortedByDescending { item -> item.createdAtMs })
        awaitClose { liveData.removeObserver(observer) }
    }

    fun downloadDirectory(): String {
        return ensureDownloadDir().absolutePath
    }

    fun enqueueEntries(entries: List<StorageEntry>) {
        val files = entries.filterNot { it.isDir }
        if (files.isEmpty()) {
            toastRepository.emitToast("当前没有可下载的文件")
            return
        }

        val activeSources = activeSourceKeys()
        val reservedNames = activeReservedFileNames()
        var skippedDuplicates = 0
        val requests = buildList {
            files.forEach { entry ->
                val sourceKey = buildSourceKey(
                    storageId = entry.storageId.value,
                    sourcePath = entry.path,
                )
                if (!activeSources.add(sourceKey)) {
                    skippedDuplicates += 1
                    return@forEach
                }
                add(
                    buildDownloadRequest(
                        storageId = entry.storageId.value,
                        sourcePath = entry.path,
                        title = entry.name.ifBlank { fallbackTitleFromPath(entry.path) },
                        sizeBytes = entry.size?.toLong(),
                        reservedNames = reservedNames,
                    )
                )
            }
        }
        if (requests.isEmpty()) {
            toastRepository.emitToast("所选文件已在下载队列中")
            return
        }
        persistRecords(records.value + requests.map { it.record })
        workManager.enqueue(requests.map { it.request })
        toastRepository.emitToast(
            if (skippedDuplicates > 0) {
                "已加入下载队列，重复任务已跳过"
            } else {
                "已加入下载队列"
            }
        )
    }

    fun enqueueCurrentMusic(music: Music) {
        if (hasActiveTask(storageId = music.loc.storageId.value, sourcePath = music.loc.path)) {
            toastRepository.emitToast("当前歌曲已在下载队列中")
            return
        }
        val request = buildDownloadRequest(
            storageId = music.loc.storageId.value,
            sourcePath = music.loc.path,
            title = music.meta.title.ifBlank { fallbackTitleFromPath(music.loc.path) },
            sizeBytes = null,
            reservedNames = activeReservedFileNames(),
        )
        persistRecords(records.value + request.record)
        workManager.enqueue(request.request)
        toastRepository.emitToast("已加入下载队列")
    }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    fun retry(task: DownloadTaskItem) {
        if (hasActiveTask(storageId = task.storageId, sourcePath = task.sourcePath)) {
            toastRepository.emitToast("该任务已在下载队列中")
            return
        }
        val request = buildDownloadRequest(
            storageId = task.storageId,
            sourcePath = task.sourcePath,
            title = task.title,
            sizeBytes = task.totalBytes,
            reservedNames = activeReservedFileNames(),
        )
        persistRecords(records.value + request.record)
        workManager.enqueue(request.request)
        toastRepository.emitToast("已重新加入下载队列")
    }

    private fun buildDownloadRequest(
        storageId: Long,
        sourcePath: String,
        title: String,
        sizeBytes: Long?,
        reservedNames: MutableSet<String>? = null,
    ): BuiltDownloadRequest {
        val input = buildInputData(
            storageId = storageId,
            sourcePath = sourcePath,
            title = title,
            sizeBytes = sizeBytes,
            reservedNames = reservedNames,
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .addTag(DOWNLOAD_WORK_TAG)
            .build()
        return BuiltDownloadRequest(
            request = request,
            record = toPersistedRecord(request.id, input),
        )
    }

    private fun buildInputData(
        storageId: Long,
        sourcePath: String,
        title: String,
        sizeBytes: Long?,
        reservedNames: MutableSet<String>? = null,
    ): Data {
        val fileName = buildUniqueFileName(
            title = title,
            sourcePath = sourcePath,
            reservedNames = reservedNames,
        )
        val targetPath = File(ensureDownloadDir(), fileName).absolutePath
        return workDataOf(
            DownloadWorkKeys.STORAGE_ID to storageId,
            DownloadWorkKeys.SOURCE_PATH to sourcePath,
            DownloadWorkKeys.TITLE to title,
            DownloadWorkKeys.FILE_NAME to fileName,
            DownloadWorkKeys.TARGET_PATH to targetPath,
            DownloadWorkKeys.SIZE_BYTES to (sizeBytes ?: -1L),
            DownloadWorkKeys.CREATED_AT_MS to System.currentTimeMillis(),
        )
    }

    private fun buildUniqueFileName(
        title: String,
        sourcePath: String,
        reservedNames: MutableSet<String>? = null,
    ): String {
        val sanitized = sanitizeFileName(
            title = title,
            sourcePath = sourcePath,
        )
        val dotIndex = sanitized.lastIndexOf('.')
        val base = if (dotIndex > 0) sanitized.substring(0, dotIndex) else sanitized
        val ext = if (dotIndex > 0) sanitized.substring(dotIndex) else ""
        val targetDir = ensureDownloadDir()
        var candidate = sanitized
        var index = 1
        while (
            candidate in (reservedNames ?: emptySet<String>()) ||
            File(targetDir, candidate).exists()
        ) {
            candidate = if (ext.isNotBlank()) {
                "$base ($index)$ext"
            } else {
                "$base ($index)"
            }
            index += 1
        }
        reservedNames?.add(candidate)
        return candidate
    }

    private fun sanitizeFileName(
        title: String,
        sourcePath: String,
    ): String {
        val raw = title.ifBlank { fallbackTitleFromPath(sourcePath) }
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace('\n', ' ')
            .trim()
        val candidate = cleaned.ifBlank { "track" }
        return if (candidate.contains('.')) {
            candidate
        } else {
            val extension = sourcePath
                .substringAfterLast('.', "")
                .takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
            if (extension != null) {
                "$candidate.$extension"
            } else {
                candidate
            }
        }
    }

    private fun fallbackTitleFromPath(path: String): String {
        return path.substringAfterLast('/').ifBlank { "track" }
    }

    private fun hasActiveTask(
        storageId: Long,
        sourcePath: String,
    ): Boolean {
        val targetKey = buildSourceKey(storageId = storageId, sourcePath = sourcePath)
        return records.value.any { record ->
            record.toDownloadStatus().active &&
                buildSourceKey(record.storageId, record.sourcePath) == targetKey
        }
    }

    private fun activeSourceKeys(): MutableSet<String> {
        return records.value
            .filter { record -> record.toDownloadStatus().active }
            .mapTo(linkedSetOf()) { record ->
                buildSourceKey(record.storageId, record.sourcePath)
            }
    }

    private fun activeReservedFileNames(): MutableSet<String> {
        return records.value
            .filter { record -> record.toDownloadStatus().active }
            .mapNotNullTo(linkedSetOf()) { record ->
                record.fileName.ifBlank {
                    File(record.destinationPath).name
                }.takeIf { it.isNotBlank() }
            }
    }

    private fun buildSourceKey(
        storageId: Long,
        sourcePath: String,
    ): String {
        return "$storageId::$sourcePath"
    }

    private fun ensureDownloadDir(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(appContext.filesDir, "downloads")
        return File(base, "downloads").apply { mkdirs() }
    }

    private fun mergeWithWorkInfos(
        infos: List<WorkInfo>,
    ): List<DownloadTaskItem> {
        val infoById = infos.associateBy { info -> info.id.toString() }
        val updated = records.value.map { record ->
            val info = infoById[record.id] ?: return@map record
            val progress = info.progress
            val output = info.outputData
            record.copy(
                status = info.state.toDownloadStatus().name,
                bytesDownloaded = progress.longOrNull(DownloadWorkKeys.PROGRESS_BYTES)
                    ?: output.longOrNull(DownloadWorkKeys.OUTPUT_DOWNLOADED_BYTES)
                    ?: record.bytesDownloaded,
                totalBytes = progress.longOrNull(DownloadWorkKeys.PROGRESS_TOTAL_BYTES)
                    ?: record.totalBytes,
                destinationPath = output.getString(DownloadWorkKeys.OUTPUT_DEST_PATH)
                    ?: record.destinationPath,
                errorMessage = output.getString(DownloadWorkKeys.OUTPUT_ERROR_MESSAGE)
                    ?: record.errorMessage,
            )
        }
        if (updated != records.value) {
            persistRecords(updated)
        }
        return updated.map(::toTaskItem)
    }

    private fun toPersistedRecord(
        id: UUID,
        input: Data,
    ): PersistedDownloadRecord {
        return PersistedDownloadRecord(
            id = id.toString(),
            title = input.getString(DownloadWorkKeys.TITLE).orEmpty(),
            sourcePath = input.getString(DownloadWorkKeys.SOURCE_PATH).orEmpty(),
            fileName = input.getString(DownloadWorkKeys.FILE_NAME).orEmpty(),
            storageId = input.getLong(DownloadWorkKeys.STORAGE_ID, 0L),
            createdAtMs = input.getLong(DownloadWorkKeys.CREATED_AT_MS, System.currentTimeMillis()),
            status = DownloadTaskStatus.QUEUED.name,
            bytesDownloaded = 0L,
            totalBytes = input.longOrNull(DownloadWorkKeys.SIZE_BYTES),
            destinationPath = input.getString(DownloadWorkKeys.TARGET_PATH).orEmpty(),
            errorMessage = null,
        )
    }

    private fun toTaskItem(
        record: PersistedDownloadRecord,
    ): DownloadTaskItem {
        return DownloadTaskItem(
            id = UUID.fromString(record.id),
            title = record.title,
            sourcePath = record.sourcePath,
            fileName = record.fileName,
            storageId = record.storageId,
            createdAtMs = record.createdAtMs,
            status = runCatching { DownloadTaskStatus.valueOf(record.status) }
                .getOrDefault(DownloadTaskStatus.QUEUED),
            bytesDownloaded = record.bytesDownloaded.coerceAtLeast(0L),
            totalBytes = record.totalBytes?.takeIf { it > 0L },
            destinationPath = record.destinationPath,
            errorMessage = record.errorMessage,
        )
    }

    private fun persistRecords(
        items: List<PersistedDownloadRecord>,
    ) {
        val ordered = items
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtMs }
        prefs.edit()
            .putString(
                KEY_TASKS_JSON,
                json.encodeToString(ListSerializer(PersistedDownloadRecord.serializer()), ordered),
            )
            .apply()
        records.value = ordered
    }

    private fun loadPersisted(): List<PersistedDownloadRecord> {
        val raw = prefs.getString(KEY_TASKS_JSON, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PersistedDownloadRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}

private fun Data.longOrNull(key: String): Long? {
    val value = getLong(key, -1L)
    return value.takeIf { it >= 0L }
}

private fun WorkInfo.State.toDownloadStatus(): DownloadTaskStatus {
    return when (this) {
        WorkInfo.State.ENQUEUED -> DownloadTaskStatus.QUEUED
        WorkInfo.State.RUNNING -> DownloadTaskStatus.RUNNING
        WorkInfo.State.SUCCEEDED -> DownloadTaskStatus.COMPLETED
        WorkInfo.State.FAILED -> DownloadTaskStatus.FAILED
        WorkInfo.State.CANCELLED -> DownloadTaskStatus.CANCELLED
        WorkInfo.State.BLOCKED -> DownloadTaskStatus.BLOCKED
    }
}

private fun PersistedDownloadRecord.toDownloadStatus(): DownloadTaskStatus {
    return runCatching { DownloadTaskStatus.valueOf(status) }
        .getOrDefault(DownloadTaskStatus.QUEUED)
}
