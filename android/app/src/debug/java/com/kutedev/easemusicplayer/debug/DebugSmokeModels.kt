package com.kutedev.easemusicplayer.debug

import android.util.Base64
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DIRECT_HTTP
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DOWNLOADED_CONTENT
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DOWNLOADED_FILE
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_LOCAL_FILE
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_STREAM_FALLBACK
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.ease_client_schema.StorageType

const val DEBUG_SMOKE_ACTION = "com.kutedev.easemusicplayer.debug.SMOKE"
const val DEBUG_SMOKE_EXTRA_TOKEN = "token"
const val DEBUG_SMOKE_EXTRA_PAYLOAD_B64 = "payload_b64"
const val DEBUG_SMOKE_EXTRA_REQUEST_ID = "request_id"
const val DEBUG_SMOKE_TOKEN = "listen-to-list-debug-smoke-v1"
const val DEBUG_SMOKE_RESULT_FILE = "debug-smoke-last.json"
const val DEBUG_SMOKE_LOG_PREFIX = "DEBUG_SMOKE_RESULT"

val debugSmokeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

@Serializable
enum class DebugSmokeResolverMode {
    DIRECT_HTTP,
    LOCAL_FILE,
    DOWNLOADED_FILE,
    DOWNLOADED_CONTENT,
    STREAM_FALLBACK,
}

@Serializable
enum class DebugSmokeStorageType {
    LOCAL,
    WEBDAV,
    ONE_DRIVE,
    OPEN_LIST,
    ;

    fun toStorageType(): StorageType {
        return when (this) {
            LOCAL -> StorageType.LOCAL
            WEBDAV -> StorageType.WEBDAV
            ONE_DRIVE -> StorageType.ONE_DRIVE
            OPEN_LIST -> StorageType.OPEN_LIST
        }
    }
}

@Serializable
data class DebugSmokeStoragePayload(
    val type: DebugSmokeStorageType,
    val alias: String,
    val addr: String = "",
    val username: String = "",
    val password: String = "",
    val isAnonymous: Boolean = false,
    val replaceExistingAlias: Boolean = true,
)

@Serializable
data class DebugSmokePlaylistPayload(
    val folderPath: String,
    val targetEntryPath: String,
    val playlistName: String,
)

@Serializable
data class DebugSmokePlayPayload(
    val auto: Boolean = true,
    val seekToMs: Long = 0,
    val awaitReadyTimeoutMs: Long = 10_000,
)

@Serializable
data class DebugSmokeDownloadPayload(
    val targetEntryPath: String? = null,
    val waitTimeoutMs: Long = 20_000,
)

@Serializable
data class DebugSmokeExistingPlaybackPayload(
    val playlistId: Long,
    val musicId: Long,
)

@Serializable
data class DebugSmokeAssertions(
    val expectedResolverMode: DebugSmokeResolverMode? = null,
    val requiredSourceTags: List<String> = emptyList(),
    val requireCurrentMetadataDuration: Boolean = false,
    val requireNextMetadataDuration: Boolean = false,
    val metadataWaitTimeoutMs: Long = 10_000,
)

@Serializable
data class DebugSmokeRequest(
    val requestId: String,
    val resetBefore: Boolean = true,
    val storage: DebugSmokeStoragePayload,
    val playlist: DebugSmokePlaylistPayload,
    val existingPlayback: DebugSmokeExistingPlaybackPayload? = null,
    val download: DebugSmokeDownloadPayload? = null,
    val play: DebugSmokePlayPayload = DebugSmokePlayPayload(),
    val assertions: DebugSmokeAssertions = DebugSmokeAssertions(),
)

@Serializable
data class DebugSmokeResult(
    val requestId: String,
    val status: String,
    val stage: String,
    val message: String,
    val storageId: Long? = null,
    val playlistId: Long? = null,
    val musicId: Long? = null,
    val targetEntryPath: String? = null,
    val durationMs: Long? = null,
    val expectedResolverMode: DebugSmokeResolverMode? = null,
    val actualResolverMode: DebugSmokeResolverMode? = null,
    val resolvedUri: String? = null,
    val routeHistory: List<DebugSmokeRouteRecord> = emptyList(),
    val currentMetadataDurationSynced: Boolean? = null,
    val nextMetadataDurationSynced: Boolean? = null,
)

@Serializable
data class DebugSmokeRouteRecord(
    val musicId: Long? = null,
    val route: String? = null,
    val resolvedUri: String? = null,
    val sourceTag: String? = null,
    val resolverMode: DebugSmokeResolverMode? = null,
    val routeRefreshCount: Int = 0,
    val recoverySkipCount: Int = 0,
    val cacheBypassCount: Int = 0,
    val metadataFailureCount: Int = 0,
    val lastPlaybackErrorCode: Int? = null,
    val lastPlaybackErrorName: String? = null,
    val lastCacheBypassReason: Int? = null,
    val lastMetadataFailureMusicId: Long? = null,
    val lastMetadataFailureStage: String? = null,
    val lastMetadataFailureMessage: String? = null,
)

fun decodeDebugSmokePayload(payloadB64: String): DebugSmokeRequest {
    val payloadJson = String(Base64.decode(payloadB64, Base64.DEFAULT), Charsets.UTF_8)
    return debugSmokeJson.decodeFromString(DebugSmokeRequest.serializer(), payloadJson)
}

fun encodeDebugSmokeResult(result: DebugSmokeResult): String {
    return debugSmokeJson.encodeToString(DebugSmokeResult.serializer(), result)
}

fun writeDebugSmokeResult(file: File, result: DebugSmokeResult) {
    file.parentFile?.mkdirs()
    file.writeText(encodeDebugSmokeResult(result))
}

fun debugSmokeResolverModeFromRoute(route: String?): DebugSmokeResolverMode? {
    return when (route) {
        PLAYBACK_ROUTE_DIRECT_HTTP -> DebugSmokeResolverMode.DIRECT_HTTP
        PLAYBACK_ROUTE_LOCAL_FILE -> DebugSmokeResolverMode.LOCAL_FILE
        PLAYBACK_ROUTE_DOWNLOADED_FILE -> DebugSmokeResolverMode.DOWNLOADED_FILE
        PLAYBACK_ROUTE_DOWNLOADED_CONTENT -> DebugSmokeResolverMode.DOWNLOADED_CONTENT
        PLAYBACK_ROUTE_STREAM_FALLBACK -> DebugSmokeResolverMode.STREAM_FALLBACK
        else -> null
    }
}
