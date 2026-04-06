package com.kutedev.easemusicplayer.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ease_client_backend.easeError

private const val DEBUG_SESSION_COMMAND_RECEIVER_TIMEOUT_MS = 60_000L

class DebugSessionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugSessionCommandReceiverEntryPoint::class.java,
        ).debugSessionCommandExecutor()

        scope.launch {
            try {
                val requestId = intent.getStringExtra(DEBUG_SESSION_COMMAND_EXTRA_REQUEST_ID)
                    ?: "debug-session-command"
                val token = intent.getStringExtra(DEBUG_SESSION_COMMAND_EXTRA_TOKEN)
                val payloadB64 = intent.getStringExtra(DEBUG_SESSION_COMMAND_EXTRA_PAYLOAD_B64)
                    ?: error("缺少 payload_b64")
                if (token != DEBUG_SESSION_COMMAND_TOKEN) {
                    val result = DebugSessionCommandResult(
                        requestId = requestId,
                        status = "error",
                        stage = "auth",
                        message = "debug session command token 无效",
                    )
                    writePendingResult(pendingResult, result)
                    return@launch
                }

                val request = decodeDebugSessionCommandPayload(payloadB64)
                val result = withTimeoutOrNull(DEBUG_SESSION_COMMAND_RECEIVER_TIMEOUT_MS) {
                    executor.execute(request)
                } ?: DebugSessionCommandResult(
                    requestId = request.requestId,
                    status = "error",
                    stage = "timeout",
                    message = "debug session command receiver timeout after ${DEBUG_SESSION_COMMAND_RECEIVER_TIMEOUT_MS}ms",
                    command = request.command,
                )
                writePendingResult(pendingResult, result)
            } catch (error: Exception) {
                easeError("debug session command receiver failed: $error")
                val requestId = intent.getStringExtra(DEBUG_SESSION_COMMAND_EXTRA_REQUEST_ID)
                    ?: "debug-session-command"
                val result = DebugSessionCommandResult(
                    requestId = requestId,
                    status = "error",
                    stage = "receiver",
                    message = error.message ?: error.toString(),
                )
                writePendingResult(pendingResult, result)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun writePendingResult(
        pendingResult: PendingResult,
        result: DebugSessionCommandResult,
    ) {
        val payloadB64 = Base64.encodeToString(
            encodeDebugSessionCommandResult(result).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        pendingResult.setResultCode(
            if (result.status == "ok") Activity.RESULT_OK else Activity.RESULT_CANCELED,
        )
        pendingResult.setResultData(payloadB64)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugSessionCommandReceiverEntryPoint {
    fun debugSessionCommandExecutor(): DebugSessionCommandExecutor
}
