package com.kutedev.easemusicplayer.debug

import android.content.ComponentName
import android.content.Context
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.kutedev.easemusicplayer.core.PlaybackService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun connectDebugMediaController(context: Context): MediaController {
    val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    ).buildAsync()
    return future.await()
}

internal suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            MoreExecutors.directExecutor()
        )
        continuation.invokeOnCancellation {
            cancel(true)
        }
    }
}
