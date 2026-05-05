package com.kutedev.easemusicplayer.debug

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.session.SessionCommand
import com.kutedev.easemusicplayer.core.PLAYER_CYCLE_PLAY_MODE_COMMAND
import com.kutedev.easemusicplayer.core.PLAYER_STOP_PLAYBACK_COMMAND
import com.kutedev.easemusicplayer.core.PlaybackDiagnostics
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.easeError
import javax.inject.Inject

class DebugSessionCommandExecutor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerRepository: PlayerRepository,
) {
    suspend fun execute(request: DebugSessionCommandRequest): DebugSessionCommandResult {
        return try {
            val controller = withContext(Dispatchers.Main.immediate) {
                connectDebugMediaController(appContext)
            }
            val command = when (request.command) {
                DebugSessionCommandType.CYCLE_PLAY_MODE ->
                    SessionCommand(PLAYER_CYCLE_PLAY_MODE_COMMAND, Bundle.EMPTY)
                DebugSessionCommandType.STOP_PLAYBACK ->
                    SessionCommand(PLAYER_STOP_PLAYBACK_COMMAND, Bundle.EMPTY)
                DebugSessionCommandType.READ_STATE -> null
            }
            if (command != null) {
                withContext(Dispatchers.Main.immediate) {
                    controller.sendCustomCommand(command, Bundle.EMPTY).await()
                }
            }
            delay(request.waitAfterCommandMs)
            val controllerState = withContext(Dispatchers.Main.immediate) {
                ControllerState(
                    playbackState = controller.playbackState,
                    isPlaying = controller.isPlaying,
                    currentTitle = controller.mediaMetadata.title?.toString(),
                    currentPositionMs = controller.currentPosition,
                    bufferedPositionMs = controller.bufferedPosition,
                    durationMs = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 },
                )
            }

            DebugSessionCommandResult(
                requestId = request.requestId,
                status = "ok",
                stage = "done",
                message = "debug session command 执行成功",
                command = request.command,
                playMode = playerRepository.playMode.value.name,
                playbackState = controllerState.playbackState,
                isPlaying = controllerState.isPlaying,
                currentTitle = controllerState.currentTitle,
                currentMusicId = playerRepository.music.value?.meta?.id?.value,
                currentQueueEntryId = playerRepository.currentQueueEntryIdValue(),
                currentPositionMs = controllerState.currentPositionMs,
                bufferedPositionMs = controllerState.bufferedPositionMs,
                durationMs = controllerState.durationMs,
                routeHistory = playbackRouteHistory(),
            )
        } catch (error: Exception) {
            easeError("debug session command execute failed: $error")
            DebugSessionCommandResult(
                requestId = request.requestId,
                status = "error",
                stage = "executor",
                message = error.message ?: error.toString(),
                command = request.command,
            )
        }
    }

    private fun playbackRouteHistory(): List<DebugSmokeRouteRecord> {
        return PlaybackDiagnostics.historySnapshot().map { snapshot ->
            DebugSmokeRouteRecord(
                musicId = snapshot.musicId,
                route = snapshot.route,
                resolvedUri = snapshot.resolvedUri,
                sourceTag = snapshot.sourceTag,
                resolverMode = debugSmokeResolverModeFromRoute(snapshot.route),
                routeRefreshCount = snapshot.routeRefreshCount,
                recoverySkipCount = snapshot.recoverySkipCount,
                cacheBypassCount = snapshot.cacheBypassCount,
                metadataFailureCount = snapshot.metadataFailureCount,
                lastPlaybackErrorCode = snapshot.lastPlaybackErrorCode,
                lastPlaybackErrorName = snapshot.lastPlaybackErrorName,
                lastCacheBypassReason = snapshot.lastCacheBypassReason,
                lastMetadataFailureMusicId = snapshot.lastMetadataFailureMusicId,
                lastMetadataFailureStage = snapshot.lastMetadataFailureStage,
                lastMetadataFailureMessage = snapshot.lastMetadataFailureMessage,
            )
        }
    }
}

private data class ControllerState(
    val playbackState: Int,
    val isPlaying: Boolean,
    val currentTitle: String?,
    val currentPositionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long?,
)
