package com.kutedev.easemusicplayer.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import com.kutedev.easemusicplayer.singleton.DownloadTaskItem
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ease_client_backend.ArgUpsertStorage
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_backend.ctListStorageEntryChildren
import uniffi.ease_client_schema.StorageEntryLoc

@Singleton
class DebugDownloadProbeExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: Bridge,
    private val storageRepository: StorageRepository,
    private val downloadRepository: DownloadRepository,
) {
    suspend fun execute(
        request: DebugDownloadProbeRequest,
        grantedDataUri: String? = null,
        intentFlags: Int = 0,
    ): DebugDownloadProbeResult {
        return runCatching {
            bridge.initialize()
            when (request.operation) {
                DebugDownloadProbeOperation.DUMP_STATE -> dumpState(request)
                DebugDownloadProbeOperation.SET_DIRECTORY -> setDirectory(request, grantedDataUri, intentFlags)
                DebugDownloadProbeOperation.DOWNLOAD_ENTRY -> downloadEntry(request)
            }
        }.getOrElse { error ->
            easeError("debug download probe execute failed: $error")
            dumpState(
                request = request,
                status = "error",
                stage = "execute",
                message = error.message ?: error.toString(),
            )
        }.also { result ->
            writeImmediateResult(result)
        }
    }

    fun writeImmediateResult(result: DebugDownloadProbeResult) {
        Log.i("DebugDownloadProbe", "$DEBUG_DOWNLOAD_PROBE_LOG_PREFIX ${encodeDebugDownloadProbeResult(result)}")
        easeLog("$DEBUG_DOWNLOAD_PROBE_LOG_PREFIX ${encodeDebugDownloadProbeResult(result)}")
    }

    private fun dumpState(
        request: DebugDownloadProbeRequest,
        status: String = "ok",
        stage: String = "dump",
        message: String = "state dumped",
        matchedTask: DownloadTaskItem? = null,
        timeline: List<DebugDownloadProbeTaskSnapshot> = emptyList(),
    ): DebugDownloadProbeResult {
        val directoryState = downloadRepository.downloadDirectoryState.value
        val permissions = context.contentResolver.persistedUriPermissions.map { permission ->
            DebugDownloadProbePermission(
                uri = permission.uri.toString(),
                isReadPermission = permission.isReadPermission,
                isWritePermission = permission.isWritePermission,
                persistedTimeMillis = permission.persistedTime,
            )
        }
        return DebugDownloadProbeResult(
            requestId = request.requestId,
            status = status,
            stage = stage,
            message = message,
            downloadDirectorySummary = directoryState.summary,
            downloadDirectoryTreeUri = directoryState.treeUri,
            persistedPermissions = permissions,
            matchedTask = matchedTask?.toProbeSnapshot(),
            timeline = timeline,
        )
    }

    private fun setDirectory(
        request: DebugDownloadProbeRequest,
        grantedDataUri: String?,
        intentFlags: Int,
    ): DebugDownloadProbeResult {
        val uriString = request.directoryUri ?: grantedDataUri ?: error("缺少 directoryUri")
        val uri = Uri.parse(uriString)
        val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if ((intentFlags and permissionFlags) != 0) {
            context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
        }
        downloadRepository.setDownloadDirectory(uriString)
        return dumpState(
            request = request,
            stage = "set_directory",
            message = "download directory updated",
        )
    }

    private suspend fun downloadEntry(request: DebugDownloadProbeRequest): DebugDownloadProbeResult {
        val storagePayload = request.storage ?: error("缺少 storage")
        val sourcePath = request.sourcePath?.takeIf { it.isNotBlank() } ?: error("缺少 sourcePath")
        storageRepository.reload()
        val existingId = storageRepository.storages.value
            .firstOrNull { it.alias == storagePayload.alias }
            ?.id

        storageRepository.upsertStorage(
            ArgUpsertStorage(
                id = existingId,
                addr = storagePayload.addr,
                alias = storagePayload.alias,
                username = storagePayload.username,
                password = storagePayload.password,
                isAnonymous = storagePayload.isAnonymous,
                typ = storagePayload.type.toStorageType(),
                defaultPath = "/",
            )
        )
        storageRepository.reload()
        val storage = storageRepository.storages.value
            .firstOrNull { it.alias == storagePayload.alias }
            ?: error("未找到目标存储：${storagePayload.alias}")

        val targetEntry = StorageEntry(
            storageId = storage.id,
            name = sourcePath.substringAfterLast('/').ifBlank { "track" },
            path = sourcePath,
            size = null,
            isDir = false,
        )

        val beforeTasks = downloadRepository.tasks.first()
        val beforeCount = beforeTasks.size
        downloadRepository.enqueueEntries(listOf(targetEntry))

        val timeline = mutableListOf<DebugDownloadProbeTaskSnapshot>()
        var terminalTask: DownloadTaskItem? = null
        withTimeoutOrNull(request.waitTimeoutMs) {
            while (terminalTask == null) {
                val matched = downloadRepository.tasks.first()
                    .firstOrNull { task ->
                        task.storageId == targetEntry.storageId.value && task.sourcePath == targetEntry.path
                    }
                if (matched != null) {
                    val snapshot = matched.toProbeSnapshot()
                    if (timeline.lastOrNull() != snapshot) {
                        timeline += snapshot
                    }
                    if (matched.status.isTerminalForProbe()) {
                        terminalTask = matched
                        continue
                    }
                } else if (downloadRepository.tasks.first().size < beforeCount + 1) {
                    // task may still be scheduling; keep waiting
                }
                delay(request.pollIntervalMs)
            }
        }

        val task = terminalTask ?: return dumpState(
            request = request,
            status = "error",
            stage = "timeout",
            message = "等待下载任务进入终态超时：$sourcePath",
            timeline = timeline,
        )

        return dumpState(
            request = request,
            status = if (task.status == com.kutedev.easemusicplayer.singleton.DownloadTaskStatus.COMPLETED) "ok" else "error",
            stage = "download",
            message = "download status=${task.status} error=${task.errorMessage ?: ""}".trim(),
            matchedTask = task,
            timeline = timeline,
        )
    }

    @Suppress("unused")
    private suspend fun findEntry(storageId: Long, sourcePath: String): StorageEntry? {
        val parentPath = sourcePath.substringBeforeLast('/', "")
            .ifBlank { "/" }
        val response = bridge.runRaw { backend ->
            ctListStorageEntryChildren(
                backend,
                StorageEntryLoc(
                    storageId = uniffi.ease_client_schema.StorageId(storageId),
                    path = parentPath,
                )
            )
        }
        val entries = when (response) {
            is ListStorageEntryChildrenResp.Ok -> response.v1
            ListStorageEntryChildrenResp.AuthenticationFailed -> error("列目录认证失败：$parentPath")
            ListStorageEntryChildrenResp.Timeout -> error("列目录超时：$parentPath")
            ListStorageEntryChildrenResp.Unknown -> error("列目录失败：$parentPath")
        }
        return entries.firstOrNull { entry ->
            entry.entryTyp() == StorageEntryType.MUSIC && entry.path == sourcePath
        }
    }
}

private fun DownloadTaskItem.toProbeSnapshot(): DebugDownloadProbeTaskSnapshot {
    return DebugDownloadProbeTaskSnapshot(
        status = status.name,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        errorMessage = errorMessage,
    )
}
