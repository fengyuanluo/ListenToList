package com.kutedev.easemusicplayer.debug

import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kutedev.easemusicplayer.core.PLAYER_CYCLE_PLAY_MODE_COMMAND
import com.kutedev.easemusicplayer.core.PLAYER_STOP_PLAYBACK_COMMAND
import com.kutedev.easemusicplayer.singleton.PlayerRepository
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
            }
            withContext(Dispatchers.Main.immediate) {
                controller.sendCustomCommand(command, Bundle.EMPTY).await()
            }
            delay(request.waitAfterCommandMs)
            val playbackState = withContext(Dispatchers.Main.immediate) { controller.playbackState }
            val isPlaying = withContext(Dispatchers.Main.immediate) { controller.isPlaying }
            val currentTitle = withContext(Dispatchers.Main.immediate) {
                controller.mediaMetadata.title?.toString()
            }

            DebugSessionCommandResult(
                requestId = request.requestId,
                status = "ok",
                stage = "done",
                message = "debug session command 执行成功",
                command = request.command,
                playMode = playerRepository.playMode.value.name,
                playbackState = playbackState,
                isPlaying = isPlaying,
                currentTitle = currentTitle,
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
}
