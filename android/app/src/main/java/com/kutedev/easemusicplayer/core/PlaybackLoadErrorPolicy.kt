package com.kutedev.easemusicplayer.core

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object PlaybackLoadErrorPolicy {
    fun build(): LoadErrorHandlingPolicy {
        return object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return if (shouldFailFastPlaybackLoad(loadErrorInfo.exception)) {
                    C.TIME_UNSET
                } else {
                    super.getRetryDelayMsFor(loadErrorInfo)
                }
            }
        }
    }
}

internal fun shouldFailFastPlaybackLoad(error: IOException): Boolean {
    val status = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode
    if (status != null) {
        return status == 401 ||
            status == 403 ||
            status == 404 ||
            status == 408 ||
            status == 429 ||
            status >= 500
    }
    return error.findCause<FileNotFoundException>() != null ||
        error.findCause<InterruptedIOException>() != null ||
        error.findCause<SocketTimeoutException>() != null ||
        error.findCause<ConnectException>() != null ||
        error.findCause<UnknownHostException>() != null ||
        error.findCause<SocketException>() != null ||
        error.findCause<EOFException>() != null
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var cursor: Throwable? = this
    while (cursor != null) {
        if (cursor is T) {
            return cursor
        }
        cursor = cursor.cause
    }
    return null
}
