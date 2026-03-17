package com.kutedev.easemusicplayer.debug

import android.util.Base64
import com.kutedev.easemusicplayer.singleton.DownloadTaskStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

const val DEBUG_DOWNLOAD_PROBE_ACTION = "com.kutedev.easemusicplayer.debug.DOWNLOAD_PROBE"
const val DEBUG_DOWNLOAD_PROBE_EXTRA_TOKEN = "token"
const val DEBUG_DOWNLOAD_PROBE_EXTRA_PAYLOAD_B64 = "payload_b64"
const val DEBUG_DOWNLOAD_PROBE_EXTRA_REQUEST_ID = "request_id"
const val DEBUG_DOWNLOAD_PROBE_TOKEN = "listen-to-list-debug-download-probe-v1"
const val DEBUG_DOWNLOAD_PROBE_LOG_PREFIX = "DEBUG_DOWNLOAD_PROBE_RESULT"

@Serializable
enum class DebugDownloadProbeOperation {
    DUMP_STATE,
    SET_DIRECTORY,
    DOWNLOAD_ENTRY,
}

@Serializable
data class DebugDownloadProbeRequest(
    val requestId: String,
    val operation: DebugDownloadProbeOperation,
    val directoryUri: String? = null,
    val storage: DebugSmokeStoragePayload? = null,
    val sourcePath: String? = null,
    val waitTimeoutMs: Long = 120_000L,
    val pollIntervalMs: Long = 500L,
)

@Serializable
data class DebugDownloadProbePermission(
    val uri: String,
    val isReadPermission: Boolean,
    val isWritePermission: Boolean,
    val persistedTimeMillis: Long,
)

@Serializable
data class DebugDownloadProbeTaskSnapshot(
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

@Serializable
data class DebugDownloadProbeResult(
    val requestId: String,
    val status: String,
    val stage: String,
    val message: String,
    val downloadDirectorySummary: String? = null,
    val downloadDirectoryTreeUri: String? = null,
    val persistedPermissions: List<DebugDownloadProbePermission> = emptyList(),
    val matchedTask: DebugDownloadProbeTaskSnapshot? = null,
    val timeline: List<DebugDownloadProbeTaskSnapshot> = emptyList(),
)

fun decodeDebugDownloadProbePayload(payloadB64: String): DebugDownloadProbeRequest {
    val payloadJson = String(Base64.decode(payloadB64, Base64.DEFAULT), Charsets.UTF_8)
    return debugSmokeJson.decodeFromString(DebugDownloadProbeRequest.serializer(), payloadJson)
}

fun encodeDebugDownloadProbeResult(result: DebugDownloadProbeResult): String {
    return debugSmokeJson.encodeToString(DebugDownloadProbeResult.serializer(), result)
}

internal fun DownloadTaskStatus.isTerminalForProbe(): Boolean {
    return this == DownloadTaskStatus.COMPLETED ||
        this == DownloadTaskStatus.FAILED ||
        this == DownloadTaskStatus.CANCELLED
}
