package com.kutedev.easemusicplayer.debug

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

const val DEBUG_SESSION_COMMAND_ACTION = "com.kutedev.easemusicplayer.debug.SESSION_COMMAND"
const val DEBUG_SESSION_COMMAND_EXTRA_TOKEN = "token"
const val DEBUG_SESSION_COMMAND_EXTRA_PAYLOAD_B64 = "payload_b64"
const val DEBUG_SESSION_COMMAND_EXTRA_REQUEST_ID = "request_id"
const val DEBUG_SESSION_COMMAND_TOKEN = "listen-to-list-debug-session-command-v1"

@Serializable
enum class DebugSessionCommandType {
    CYCLE_PLAY_MODE,
    STOP_PLAYBACK,
}

@Serializable
data class DebugSessionCommandRequest(
    val requestId: String,
    val command: DebugSessionCommandType,
    val waitAfterCommandMs: Long = 500,
)

@Serializable
data class DebugSessionCommandResult(
    val requestId: String,
    val status: String,
    val stage: String,
    val message: String,
    val command: DebugSessionCommandType? = null,
    val playMode: String? = null,
    val playbackState: Int? = null,
    val isPlaying: Boolean? = null,
    val currentTitle: String? = null,
)

fun decodeDebugSessionCommandPayload(payloadB64: String): DebugSessionCommandRequest {
    val payloadJson = String(Base64.decode(payloadB64, Base64.DEFAULT), Charsets.UTF_8)
    return debugSmokeJson.decodeFromString(DebugSessionCommandRequest.serializer(), payloadJson)
}

fun encodeDebugSessionCommandResult(result: DebugSessionCommandResult): String {
    return debugSmokeJson.encodeToString(DebugSessionCommandResult.serializer(), result)
}
