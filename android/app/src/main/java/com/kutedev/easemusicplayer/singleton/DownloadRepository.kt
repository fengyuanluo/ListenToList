package com.kutedev.easemusicplayer.singleton

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kutedev.easemusicplayer.core.DownloadWorker
import com.kutedev.easemusicplayer.core.PlaybackSourceResolverCache
import com.kutedev.easemusicplayer.core.ResolvedMusicPlaybackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    const val TARGET_URI = "target_uri"
    const val TEMP_TARGET_PATH = "temp_target_path"
    const val TEMP_TARGET_URI = "temp_target_uri"
    const val DIRECTORY_TREE_URI = "directory_tree_uri"
    const val SIZE_BYTES = "size_bytes"
    const val CREATED_AT_MS = "created_at_ms"

    const val PROGRESS_BYTES = "progress_bytes"
    const val PROGRESS_TOTAL_BYTES = "progress_total_bytes"

    const val OUTPUT_DEST_PATH = "output_dest_path"
    const val OUTPUT_DEST_URI = "output_dest_uri"
    const val OUTPUT_DOWNLOADED_BYTES = "output_downloaded_bytes"
    const val OUTPUT_ERROR_MESSAGE = "output_error_message"
}

enum class DownloadTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
    ;

    val active: Boolean
        get() = this == QUEUED || this == RUNNING || this == BLOCKED
}

data class DownloadDirectoryState(
    val summary: String,
    val treeUri: String? = null,
    val isDefaultAppPrivate: Boolean = false,
    val errorMessage: String? = null,
)

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
    val destinationUri: String? = null,
    val errorMessage: String? = null,
) {
    val active: Boolean
        get() = status.active

    val pausable: Boolean
        get() = status.active

    val startable: Boolean
        get() = status == DownloadTaskStatus.PAUSED || status == DownloadTaskStatus.FAILED || status == DownloadTaskStatus.CANCELLED
}

@Serializable
internal data class PersistedDownloadRecord(
    val id: String,
    val workId: String? = null,
    val title: String,
    val sourcePath: String,
    val fileName: String,
    val storageId: Long,
    val createdAtMs: Long,
    val status: String,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val destinationPath: String = "",
    val destinationUri: String? = null,
    val tempPath: String? = null,
    val tempUri: String? = null,
    val directoryTreeUri: String? = null,
    val errorMessage: String? = null,
)

private data class BuiltDownloadRequest(
    val request: OneTimeWorkRequest,
    val record: PersistedDownloadRecord,
)

private data class PreparedRestartRequest(
    val request: OneTimeWorkRequest,
    val record: PersistedDownloadRecord,
)

private sealed class DownloadDirectorySpec {
    data class Default(val dir: File) : DownloadDirectorySpec()
    data class Custom(
        val treeUri: String,
        val tree: DocumentFile,
    ) : DownloadDirectorySpec()
}

private sealed class DownloadTargetSpec {
    abstract val fileName: String

    data class FilePath(
        override val fileName: String,
        val destinationPath: String,
        val tempPath: String,
    ) : DownloadTargetSpec()

    data class ContentTree(
        override val fileName: String,
        val treeUri: String,
        val destinationUri: String?,
        val tempUri: String,
    ) : DownloadTargetSpec()
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val toastRepository: ToastRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val PREFS_NAME = "download_tasks"
        private const val KEY_TASKS_JSON = "tasks_json"
        private const val KEY_DIRECTORY_TREE_URI = "download_directory_tree_uri"
        private const val KEY_DIRECTORY_LABEL = "download_directory_label"
        private const val OCTET_STREAM_MIME = "application/octet-stream"
    }

    private val workManager = WorkManager.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val records = MutableStateFlow(loadPersisted())
    private val directoryState = MutableStateFlow(loadDownloadDirectoryState())
    private val workInfoObserver = Observer<List<WorkInfo>> { infos ->
        synchronizeWithWorkInfos(infos.orEmpty())
    }

    val tasks: Flow<List<DownloadTaskItem>> = records.map { currentRecords ->
        currentRecords.map(::toTaskItem)
            .sortedByDescending { item -> item.createdAtMs }
    }
    val downloadDirectoryState = directoryState.asStateFlow()

    init {
        scope.launch(Dispatchers.Main.immediate) {
            workManager.getWorkInfosByTagLiveData(DOWNLOAD_WORK_TAG)
                .observeForever(workInfoObserver)
        }
        scope.launch {
            reconcileWorkInfosNow()
        }
    }

    fun downloadDirectory(): String {
        return directoryState.value.summary
    }

    fun setDownloadDirectory(treeUri: String) {
        val setting = runCatching { buildDownloadDirectoryState(treeUri) }.getOrNull()
        if (setting == null) {
            toastRepository.emitToast("无法使用所选下载目录")
            return
        }
        persistDownloadDirectoryState(setting)
        toastRepository.emitToast("已更新下载目录")
    }

    fun resetDownloadDirectory() {
        persistDownloadDirectoryState(defaultDownloadDirectoryState())
        toastRepository.emitToast("已恢复默认下载目录")
    }

    suspend fun enqueueEntries(entries: List<StorageEntry>) {
        val files = entries.filterNot { it.isDir }
        if (files.isEmpty()) {
            toastRepository.emitToast("当前没有可下载的文件")
            return
        }

        reconcileWorkInfosNow()
        val activeSources = activeSourceKeys()
        val reservedNames = activeReservedFileNames()
        var skippedDuplicates = 0
        val requests = runCatching {
            buildList {
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
        }.getOrElse { error ->
            toastRepository.emitToast(error.message ?: "下载目录不可用，请重新设置")
            return
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

    suspend fun enqueueCurrentMusic(music: Music) {
        reconcileWorkInfosNow()
        if (hasActiveTask(storageId = music.loc.storageId.value, sourcePath = music.loc.path)) {
            toastRepository.emitToast("当前歌曲已在下载队列中")
            return
        }
        val request = runCatching {
            buildDownloadRequest(
                storageId = music.loc.storageId.value,
                sourcePath = music.loc.path,
                title = music.meta.title.ifBlank { fallbackTitleFromPath(music.loc.path) },
                sizeBytes = null,
                reservedNames = activeReservedFileNames(),
            )
        }.getOrElse { error ->
            toastRepository.emitToast(error.message ?: "下载目录不可用，请重新设置")
            return
        }
        persistRecords(records.value + request.record)
        workManager.enqueue(request.request)
        toastRepository.emitToast("已加入下载队列")
    }

    suspend fun pause(id: UUID) {
        reconcileWorkInfosNow()
        val current = records.value.firstOrNull { it.id == id.toString() } ?: return
        val workId = current.effectiveWorkId()
        val paused = current.copy(
            status = DownloadTaskStatus.PAUSED.name,
            workId = null,
            errorMessage = null,
        )
        persistRecords(records.value.replaceRecord(paused))
        workId?.let { workManager.cancelWorkById(UUID.fromString(it)) }
    }

    suspend fun start(id: UUID) {
        reconcileWorkInfosNow()
        val current = records.value.firstOrNull { it.id == id.toString() } ?: return
        if (current.toDownloadStatus().active) {
            toastRepository.emitToast("该任务已在下载队列中")
            return
        }
        if (hasActiveTask(storageId = current.storageId, sourcePath = current.sourcePath, excludingId = current.id)) {
            toastRepository.emitToast("该任务已在下载队列中")
            return
        }

        val prepared = runCatching { buildRestartRequest(current) }.getOrElse { error ->
            toastRepository.emitToast(error.message ?: "无法启动该下载任务")
            return
        }
        persistRecords(records.value.replaceRecord(prepared.record))
        workManager.enqueue(prepared.request)
    }

    suspend fun delete(id: UUID, deleteFile: Boolean) {
        reconcileWorkInfosNow()
        val current = records.value.firstOrNull { it.id == id.toString() } ?: return
        val remaining = records.value.filterNot { it.id == current.id }
        persistRecords(remaining)
        current.effectiveWorkId()?.let { workManager.cancelWorkById(UUID.fromString(it)) }
        if (deleteFile) {
            val deletedCleanly = deleteTaskFiles(current)
            if (!deletedCleanly) {
                toastRepository.emitToast("已删除任务，但部分本地文件删除失败")
            }
        }
        PlaybackSourceResolverCache.invalidateAll()
    }

    suspend fun cancel(id: UUID) {
        pause(id)
    }

    suspend fun retry(task: DownloadTaskItem) {
        start(task.id)
    }

    fun resolveCompletedPlaybackSource(
        storageId: Long,
        sourcePath: String,
    ): ResolvedMusicPlaybackSource? {
        val record = records.value
            .asSequence()
            .filter { it.storageId == storageId && it.sourcePath == sourcePath }
            .filter { it.toDownloadStatus() == DownloadTaskStatus.COMPLETED }
            .sortedByDescending { it.createdAtMs }
            .firstOrNull()
            ?: return null

        record.destinationUri?.takeIf(::canReadContentUri)?.let { uri ->
            return ResolvedMusicPlaybackSource.ContentUri(uri)
        }
        record.destinationPath.takeIf { it.isNotBlank() }
            ?.takeIf { path -> File(path).let { it.exists() && it.isFile && it.canRead() } }
            ?.let { path ->
                return ResolvedMusicPlaybackSource.DownloadedFile(path)
            }
        val failed = record.copy(
            status = DownloadTaskStatus.FAILED.name,
            errorMessage = "离线文件不可用或已被删除",
        )
        persistRecords(records.value.replaceRecord(failed))
        PlaybackSourceResolverCache.invalidateAll()
        throw FileNotFoundException("completed download is missing for storage=$storageId path=$sourcePath")
    }

    private fun buildDownloadRequest(
        storageId: Long,
        sourcePath: String,
        title: String,
        sizeBytes: Long?,
        reservedNames: MutableSet<String>? = null,
    ): BuiltDownloadRequest {
        val businessId = UUID.randomUUID()
        val target = createTargetSpec(
            title = title,
            sourcePath = sourcePath,
            reservedNames = reservedNames,
        )
        val input = buildInputData(
            storageId = storageId,
            sourcePath = sourcePath,
            title = title,
            sizeBytes = sizeBytes,
            createdAtMs = System.currentTimeMillis(),
            target = target,
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .addTag(DOWNLOAD_WORK_TAG)
            .build()
        return BuiltDownloadRequest(
            request = request,
            record = toPersistedRecord(businessId, request.id, input, target),
        )
    }

    private fun buildRestartRequest(record: PersistedDownloadRecord): PreparedRestartRequest {
        val target = restoreTargetSpec(record)
        val input = buildInputData(
            storageId = record.storageId,
            sourcePath = record.sourcePath,
            title = record.title,
            sizeBytes = record.totalBytes,
            createdAtMs = record.createdAtMs,
            target = target,
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .addTag(DOWNLOAD_WORK_TAG)
            .build()
        return PreparedRestartRequest(
            request = request,
            record = record.copy(
                workId = request.id.toString(),
                status = DownloadTaskStatus.QUEUED.name,
                errorMessage = null,
                destinationPath = target.destinationPathOrBlank(),
                destinationUri = target.destinationUriOrNull(),
                tempPath = target.tempPathOrNull(),
                tempUri = target.tempUriOrNull(),
                directoryTreeUri = target.directoryTreeUriOrNull(),
            ),
        )
    }

    private fun buildInputData(
        storageId: Long,
        sourcePath: String,
        title: String,
        sizeBytes: Long?,
        createdAtMs: Long,
        target: DownloadTargetSpec,
    ): Data {
        return workDataOf(
            DownloadWorkKeys.STORAGE_ID to storageId,
            DownloadWorkKeys.SOURCE_PATH to sourcePath,
            DownloadWorkKeys.TITLE to title,
            DownloadWorkKeys.FILE_NAME to target.fileName,
            DownloadWorkKeys.TARGET_PATH to target.destinationPathOrBlank(),
            DownloadWorkKeys.TARGET_URI to target.destinationUriOrNull(),
            DownloadWorkKeys.TEMP_TARGET_PATH to target.tempPathOrNull(),
            DownloadWorkKeys.TEMP_TARGET_URI to target.tempUriOrNull(),
            DownloadWorkKeys.DIRECTORY_TREE_URI to target.directoryTreeUriOrNull(),
            DownloadWorkKeys.SIZE_BYTES to (sizeBytes ?: -1L),
            DownloadWorkKeys.CREATED_AT_MS to createdAtMs,
        )
    }

    private fun createTargetSpec(
        title: String,
        sourcePath: String,
        reservedNames: MutableSet<String>? = null,
    ): DownloadTargetSpec {
        val directory = currentDirectorySpec()
        val fileName = buildUniqueFileName(
            title = title,
            sourcePath = sourcePath,
            reservedNames = reservedNames,
            directory = directory,
        )
        return when (directory) {
            is DownloadDirectorySpec.Default -> {
                val destination = File(directory.dir, fileName).absolutePath
                DownloadTargetSpec.FilePath(
                    fileName = fileName,
                    destinationPath = destination,
                    tempPath = "$destination.part",
                )
            }
            is DownloadDirectorySpec.Custom -> {
                DownloadTargetSpec.ContentTree(
                    fileName = fileName,
                    treeUri = directory.treeUri,
                    destinationUri = null,
                    tempUri = createOrReuseTempDocument(directory.tree, fileName),
                )
            }
        }
    }

    private fun restoreTargetSpec(record: PersistedDownloadRecord): DownloadTargetSpec {
        val treeUri = record.directoryTreeUri
        return if (!treeUri.isNullOrBlank()) {
            val tree = resolveTreeDirectory(treeUri)
                ?: throw IOException("下载目录不可用，请重新设置")
            DownloadTargetSpec.ContentTree(
                fileName = record.fileName,
                treeUri = treeUri,
                destinationUri = record.destinationUri?.takeIf { it.isNotBlank() },
                tempUri = record.tempUri?.takeIf { it.isNotBlank() }
                    ?: createOrReuseTempDocument(tree, record.fileName),
            )
        } else {
            val destination = record.destinationPath.takeIf { it.isNotBlank() }
                ?: File(defaultDownloadDir(), record.fileName).absolutePath
            DownloadTargetSpec.FilePath(
                fileName = record.fileName,
                destinationPath = destination,
                tempPath = record.tempPath?.takeIf { it.isNotBlank() } ?: "$destination.part",
            )
        }
    }

    private fun buildUniqueFileName(
        title: String,
        sourcePath: String,
        reservedNames: MutableSet<String>? = null,
        directory: DownloadDirectorySpec,
    ): String {
        val sanitized = sanitizeFileName(
            title = title,
            sourcePath = sourcePath,
        )
        val dotIndex = sanitized.lastIndexOf('.')
        val base = if (dotIndex > 0) sanitized.substring(0, dotIndex) else sanitized
        val ext = if (dotIndex > 0) sanitized.substring(dotIndex) else ""
        var candidate = sanitized
        var index = 1
        while (
            candidate in (reservedNames ?: emptySet<String>()) ||
            fileNameExists(directory, candidate)
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

    private fun fileNameExists(directory: DownloadDirectorySpec, fileName: String): Boolean {
        return when (directory) {
            is DownloadDirectorySpec.Default -> {
                val finalFile = File(directory.dir, fileName)
                val tempFile = File(directory.dir, "$fileName.part")
                finalFile.exists() || tempFile.exists()
            }
            is DownloadDirectorySpec.Custom -> {
                directory.tree.findFile(fileName) != null || directory.tree.findFile("$fileName.part") != null
            }
        }
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
        excludingId: String? = null,
    ): Boolean {
        val targetKey = buildSourceKey(storageId = storageId, sourcePath = sourcePath)
        return records.value.any { record ->
            record.id != excludingId &&
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

    private suspend fun reconcileWorkInfosNow() {
        val infos = runCatching {
            withContext(Dispatchers.IO) {
                workManager.getWorkInfosByTag(DOWNLOAD_WORK_TAG).get()
            }
        }.getOrDefault(emptyList())
        synchronizeWithWorkInfos(infos)
    }

    private fun currentDirectorySpec(): DownloadDirectorySpec {
        val state = directoryState.value
        val treeUri = state.treeUri
        if (treeUri.isNullOrBlank()) {
            return DownloadDirectorySpec.Default(defaultDownloadDir())
        }
        val tree = resolveTreeDirectory(treeUri)
            ?: run {
                markDownloadDirectoryUnavailable(state, "下载目录不可用，请重新设置")
                throw IOException("下载目录不可用，请重新设置")
            }
        return DownloadDirectorySpec.Custom(treeUri = treeUri, tree = tree)
    }

    private fun defaultDownloadDir(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(appContext.filesDir, "downloads")
        return File(base, "downloads").apply { mkdirs() }
    }

    private fun buildDownloadDirectoryState(treeUri: String): DownloadDirectoryState {
        val tree = resolveTreeDirectory(treeUri)
            ?: throw IOException("无法使用所选下载目录")
        val label = tree.name?.takeIf { it.isNotBlank() } ?: treeUri
        return DownloadDirectoryState(summary = label, treeUri = treeUri)
    }

    private fun defaultDownloadDirectoryState(): DownloadDirectoryState {
        return DownloadDirectoryState(
            summary = defaultDownloadDir().absolutePath,
            isDefaultAppPrivate = true,
        )
    }

    private fun loadDownloadDirectoryState(): DownloadDirectoryState {
        val treeUri = prefs.getString(KEY_DIRECTORY_TREE_URI, null)
        val label = prefs.getString(KEY_DIRECTORY_LABEL, null)
        return if (treeUri.isNullOrBlank()) {
            defaultDownloadDirectoryState()
        } else {
            DownloadDirectoryState(
                summary = label?.takeIf { it.isNotBlank() } ?: treeUri,
                treeUri = treeUri,
            )
        }
    }

    private fun persistDownloadDirectoryState(state: DownloadDirectoryState) {
        prefs.edit().apply {
            if (state.treeUri.isNullOrBlank()) {
                remove(KEY_DIRECTORY_TREE_URI)
                remove(KEY_DIRECTORY_LABEL)
            } else {
                putString(KEY_DIRECTORY_TREE_URI, state.treeUri)
                putString(KEY_DIRECTORY_LABEL, state.summary)
            }
        }.apply()
        directoryState.value = state
    }

    private fun markDownloadDirectoryUnavailable(
        state: DownloadDirectoryState,
        message: String,
    ) {
        persistDownloadDirectoryState(state.copy(errorMessage = message))
    }

    private fun resolveTreeDirectory(treeUri: String): DocumentFile? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        return DocumentFile.fromTreeUri(appContext, uri)
            ?.takeIf { it.exists() && it.canWrite() }
    }

    private fun createOrReuseTempDocument(
        tree: DocumentFile,
        fileName: String,
    ): String {
        val tempName = "$fileName.part"
        val existing = tree.findFile(tempName)
        val doc = existing ?: tree.createFile(OCTET_STREAM_MIME, tempName)
        return doc?.uri?.toString() ?: throw IOException("无法在所选目录创建临时文件")
    }

    private fun guessMimeType(fileName: String, sourcePath: String): String {
        val extension = fileName.substringAfterLast('.', sourcePath.substringAfterLast('.', ""))
            .lowercase()
            .takeIf { it.isNotBlank() }
        return extension
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: OCTET_STREAM_MIME
    }

    private fun synchronizeWithWorkInfos(
        infos: List<WorkInfo>,
    ) {
        val updated = mergePersistedDownloadRecords(records.value, infos)
        if (updated != records.value) {
            persistRecords(updated)
        }
    }

    private fun toPersistedRecord(
        id: UUID,
        requestId: UUID,
        input: Data,
        target: DownloadTargetSpec,
    ): PersistedDownloadRecord {
        return PersistedDownloadRecord(
            id = id.toString(),
            workId = requestId.toString(),
            title = input.getString(DownloadWorkKeys.TITLE).orEmpty(),
            sourcePath = input.getString(DownloadWorkKeys.SOURCE_PATH).orEmpty(),
            fileName = input.getString(DownloadWorkKeys.FILE_NAME).orEmpty(),
            storageId = input.getLong(DownloadWorkKeys.STORAGE_ID, 0L),
            createdAtMs = input.getLong(DownloadWorkKeys.CREATED_AT_MS, System.currentTimeMillis()),
            status = DownloadTaskStatus.QUEUED.name,
            bytesDownloaded = 0L,
            totalBytes = input.longOrNull(DownloadWorkKeys.SIZE_BYTES),
            destinationPath = target.destinationPathOrBlank(),
            destinationUri = target.destinationUriOrNull(),
            tempPath = target.tempPathOrNull(),
            tempUri = target.tempUriOrNull(),
            directoryTreeUri = target.directoryTreeUriOrNull(),
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
            status = record.toDownloadStatus(),
            bytesDownloaded = record.bytesDownloaded.coerceAtLeast(0L),
            totalBytes = record.totalBytes?.takeIf { it > 0L },
            destinationPath = record.destinationPath,
            destinationUri = record.destinationUri,
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

    private fun deleteTaskFiles(record: PersistedDownloadRecord): Boolean {
        val deleteResults = mutableListOf<Boolean>()
        record.destinationPath.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            deleteResults += (!file.exists() || file.delete())
        }
        record.effectiveTempPath()?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            deleteResults += (!file.exists() || file.delete())
        }
        listOfNotNull(record.destinationUri, record.tempUri)
            .distinct()
            .forEach { uriString ->
                deleteResults += deleteContentUri(uriString)
            }
        return deleteResults.all { it }
    }

    private fun deleteContentUri(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return runCatching {
            val document = DocumentFile.fromSingleUri(appContext, uri)
            document == null || !document.exists() || document.delete()
        }.getOrDefault(false)
    }

    private fun canReadContentUri(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}

private fun Data.longOrNull(key: String): Long? {
    val value = getLong(key, -1L)
    return value.takeIf { it >= 0L }
}

private fun PersistedDownloadRecord.effectiveWorkId(): String? {
    return workId ?: id.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
}

private fun PersistedDownloadRecord.effectiveTempPath(): String? {
    return tempPath?.takeIf { it.isNotBlank() }
        ?: destinationPath.takeIf { it.isNotBlank() }?.let { "$it.part" }
}

private fun PersistedDownloadRecord.toDownloadStatus(): DownloadTaskStatus {
    return runCatching { DownloadTaskStatus.valueOf(status) }
        .getOrDefault(DownloadTaskStatus.QUEUED)
}

private fun List<PersistedDownloadRecord>.replaceRecord(record: PersistedDownloadRecord): List<PersistedDownloadRecord> {
    return map { current -> if (current.id == record.id) record else current }
}

private fun DownloadTargetSpec.destinationPathOrBlank(): String {
    return when (this) {
        is DownloadTargetSpec.FilePath -> destinationPath
        is DownloadTargetSpec.ContentTree -> ""
    }
}

private fun DownloadTargetSpec.destinationUriOrNull(): String? {
    return when (this) {
        is DownloadTargetSpec.FilePath -> null
        is DownloadTargetSpec.ContentTree -> destinationUri
    }
}

private fun DownloadTargetSpec.tempPathOrNull(): String? {
    return when (this) {
        is DownloadTargetSpec.FilePath -> tempPath
        is DownloadTargetSpec.ContentTree -> null
    }
}

private fun DownloadTargetSpec.tempUriOrNull(): String? {
    return when (this) {
        is DownloadTargetSpec.FilePath -> null
        is DownloadTargetSpec.ContentTree -> tempUri
    }
}

private fun DownloadTargetSpec.directoryTreeUriOrNull(): String? {
    return when (this) {
        is DownloadTargetSpec.FilePath -> null
        is DownloadTargetSpec.ContentTree -> treeUri
    }
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

private fun WorkInfo.State.isTerminal(): Boolean {
    return this == WorkInfo.State.SUCCEEDED || this == WorkInfo.State.FAILED || this == WorkInfo.State.CANCELLED
}

internal fun mergePersistedDownloadRecords(
    currentRecords: List<PersistedDownloadRecord>,
    infos: List<WorkInfo>,
): List<PersistedDownloadRecord> {
    val infoById = infos.associateBy { info -> info.id.toString() }
    return currentRecords.map { record ->
        val info = infoById[record.effectiveWorkId()] ?: return@map record
        val progress = info.progress
        val output = info.outputData
        val nextStatus = if (
            record.toDownloadStatus() == DownloadTaskStatus.PAUSED &&
            info.state == WorkInfo.State.CANCELLED
        ) {
            DownloadTaskStatus.PAUSED.name
        } else {
            info.state.toDownloadStatus().name
        }
        record.copy(
            workId = if (nextStatus == DownloadTaskStatus.PAUSED.name || info.state.isTerminal()) null else info.id.toString(),
            status = nextStatus,
            bytesDownloaded = progress.longOrNull(DownloadWorkKeys.PROGRESS_BYTES)
                ?: output.longOrNull(DownloadWorkKeys.OUTPUT_DOWNLOADED_BYTES)
                ?: record.bytesDownloaded,
            totalBytes = progress.longOrNull(DownloadWorkKeys.PROGRESS_TOTAL_BYTES)
                ?: record.totalBytes,
            destinationPath = output.getString(DownloadWorkKeys.OUTPUT_DEST_PATH)
                ?: record.destinationPath,
            destinationUri = output.getString(DownloadWorkKeys.OUTPUT_DEST_URI)
                ?: record.destinationUri,
            errorMessage = if (nextStatus == DownloadTaskStatus.PAUSED.name) {
                null
            } else {
                output.getString(DownloadWorkKeys.OUTPUT_ERROR_MESSAGE)
                    ?: record.errorMessage
            },
        )
    }
}
