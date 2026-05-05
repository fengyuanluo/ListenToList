package com.kutedev.easemusicplayer.core

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun isRecoverablePlaybackError(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
        return true
    }
    if (error.findCause<FileNotFoundException>() != null) {
        return true
    }
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
        return true
    }
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
        return true
    }
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
        return true
    }
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
        return true
    }
    if (hasTransientHttpStatus(error)) {
        return true
    }
    return hasTransientNetworkCause(error)
}

private fun hasTransientHttpStatus(error: PlaybackException): Boolean {
    val responseCode = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode ?: return false
    return responseCode >= 500 || responseCode == 408 || responseCode == 429
}

private fun hasTransientNetworkCause(error: PlaybackException): Boolean {
    return error.findCause<InterruptedIOException>() != null ||
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
