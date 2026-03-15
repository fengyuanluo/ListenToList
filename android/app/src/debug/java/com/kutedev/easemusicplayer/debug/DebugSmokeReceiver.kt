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
import uniffi.ease_client_backend.easeLog

private const val DEBUG_SMOKE_RECEIVER_TIMEOUT_MS = 35_000L

class DebugSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugSmokeReceiverEntryPoint::class.java,
        ).debugSmokeExecutor()

        scope.launch {
            try {
                val requestId = intent.getStringExtra(DEBUG_SMOKE_EXTRA_REQUEST_ID) ?: "debug-smoke"
                val token = intent.getStringExtra(DEBUG_SMOKE_EXTRA_TOKEN)
                val payloadB64 = intent.getStringExtra(DEBUG_SMOKE_EXTRA_PAYLOAD_B64)
                    ?: error("缺少 payload_b64")
                if (token != DEBUG_SMOKE_TOKEN) {
                    val result = DebugSmokeResult(
                        requestId = requestId,
                        status = "error",
                        stage = "auth",
                        message = "debug smoke token 无效",
                    )
                    executor.writeImmediateResult(result)
                    writePendingResult(pendingResult, result)
                    return@launch
                }

                val request = decodeDebugSmokePayload(payloadB64)
                val result = withTimeoutOrNull(DEBUG_SMOKE_RECEIVER_TIMEOUT_MS) {
                    executor.execute(request)
                } ?: DebugSmokeResult(
                    requestId = request.requestId,
                    status = "error",
                    stage = "timeout",
                    message = "debug smoke receiver timeout after ${DEBUG_SMOKE_RECEIVER_TIMEOUT_MS}ms",
                ).also { timeoutResult ->
                    executor.writeImmediateResult(timeoutResult)
                }
                writePendingResult(pendingResult, result)
            } catch (error: Exception) {
                easeError("debug smoke receiver failed: $error")
                val requestId = intent.getStringExtra(DEBUG_SMOKE_EXTRA_REQUEST_ID) ?: "debug-smoke"
                val result = DebugSmokeResult(
                    requestId = requestId,
                    status = "error",
                    stage = "receiver",
                    message = error.message ?: error.toString(),
                )
                executor.writeImmediateResult(result)
                writePendingResult(pendingResult, result)
            } finally {
                easeLog("debug smoke receiver finished")
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun writePendingResult(
        pendingResult: PendingResult,
        result: DebugSmokeResult,
    ) {
        val payloadB64 = Base64.encodeToString(
            encodeDebugSmokeResult(result).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        pendingResult.setResultCode(
            if (result.status == "ok") Activity.RESULT_OK else Activity.RESULT_CANCELED
        )
        pendingResult.setResultData(payloadB64)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugSmokeReceiverEntryPoint {
    fun debugSmokeExecutor(): DebugSmokeExecutor
}
