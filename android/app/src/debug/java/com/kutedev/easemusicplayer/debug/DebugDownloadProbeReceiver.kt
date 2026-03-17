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

private const val DEBUG_DOWNLOAD_PROBE_RECEIVER_TIMEOUT_MS = 180_000L

class DebugDownloadProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugDownloadProbeReceiverEntryPoint::class.java,
        ).debugDownloadProbeExecutor()

        scope.launch {
            try {
                val requestId = intent.getStringExtra(DEBUG_DOWNLOAD_PROBE_EXTRA_REQUEST_ID) ?: "debug-download-probe"
                val token = intent.getStringExtra(DEBUG_DOWNLOAD_PROBE_EXTRA_TOKEN)
                val payloadB64 = intent.getStringExtra(DEBUG_DOWNLOAD_PROBE_EXTRA_PAYLOAD_B64)
                    ?: error("缺少 payload_b64")
                if (token != DEBUG_DOWNLOAD_PROBE_TOKEN) {
                    val result = DebugDownloadProbeResult(
                        requestId = requestId,
                        status = "error",
                        stage = "auth",
                        message = "debug download probe token 无效",
                    )
                    executor.writeImmediateResult(result)
                    writePendingResult(pendingResult, result)
                    return@launch
                }
                val request = decodeDebugDownloadProbePayload(payloadB64)
                val result = withTimeoutOrNull(DEBUG_DOWNLOAD_PROBE_RECEIVER_TIMEOUT_MS) {
                    executor.execute(
                        request = request,
                        grantedDataUri = intent.dataString,
                        intentFlags = intent.flags,
                    )
                } ?: DebugDownloadProbeResult(
                    requestId = request.requestId,
                    status = "error",
                    stage = "timeout",
                    message = "debug download probe receiver timeout after ${DEBUG_DOWNLOAD_PROBE_RECEIVER_TIMEOUT_MS}ms",
                ).also { timeoutResult ->
                    executor.writeImmediateResult(timeoutResult)
                }
                writePendingResult(pendingResult, result)
            } catch (error: Exception) {
                easeError("debug download probe receiver failed: $error")
                val requestId = intent.getStringExtra(DEBUG_DOWNLOAD_PROBE_EXTRA_REQUEST_ID) ?: "debug-download-probe"
                val result = DebugDownloadProbeResult(
                    requestId = requestId,
                    status = "error",
                    stage = "receiver",
                    message = error.message ?: error.toString(),
                )
                executor.writeImmediateResult(result)
                writePendingResult(pendingResult, result)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun writePendingResult(
        pendingResult: PendingResult,
        result: DebugDownloadProbeResult,
    ) {
        val payloadB64 = Base64.encodeToString(
            encodeDebugDownloadProbeResult(result).toByteArray(Charsets.UTF_8),
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
interface DebugDownloadProbeReceiverEntryPoint {
    fun debugDownloadProbeExecutor(): DebugDownloadProbeExecutor
}
