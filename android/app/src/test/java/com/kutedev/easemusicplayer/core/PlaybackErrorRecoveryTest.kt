package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Collections
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackErrorRecoveryTest {
    @Test
    fun fileNotFoundError_isRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            cause = FileNotFoundException("missing local file"),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun networkFailureError_isRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            cause = ConnectException("connection reset"),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun networkTimeoutError_isRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            cause = SocketTimeoutException("read timeout"),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun badHttpStatusError_isRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            cause = httpStatusError(503),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun readPositionOutOfRangeError_isRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            cause = EOFException("stream ended early"),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun transientNetworkCause_isRecoverableEvenWhenErrorCodeIsGenericIo() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            cause = SocketTimeoutException("chunk timeout"),
        )

        assertTrue(isRecoverablePlaybackError(error))
    }

    @Test
    fun playbackLoadTimeout_failsFastToPlayerRecovery() {
        val error = IOException("read failed", SocketTimeoutException("read timeout"))

        assertTrue(shouldFailFastPlaybackLoad(error))
    }

    @Test
    fun playbackLoadServerError_failsFastToPlayerRecovery() {
        val error = httpStatusError(503)

        assertTrue(shouldFailFastPlaybackLoad(error))
    }

    @Test
    fun playbackLoadParserError_keepsDefaultRetryPolicy() {
        val error = IOException("parser retry")

        assertFalse(shouldFailFastPlaybackLoad(error))
    }

    @Test
    fun shouldRetryCurrentAfterRecoverableError_onlyAllowsOneRetryWithoutCandidate() {
        assertTrue(
            shouldRetryCurrentAfterRecoverableError(
                hasRecoveryCandidate = false,
                currentEntryInSnapshot = true,
                currentRetryAttempted = false,
            )
        )
        assertFalse(
            shouldRetryCurrentAfterRecoverableError(
                hasRecoveryCandidate = true,
                currentEntryInSnapshot = true,
                currentRetryAttempted = false,
            )
        )
        assertFalse(
            shouldRetryCurrentAfterRecoverableError(
                hasRecoveryCandidate = false,
                currentEntryInSnapshot = false,
                currentRetryAttempted = false,
            )
        )
        assertFalse(
            shouldRetryCurrentAfterRecoverableError(
                hasRecoveryCandidate = false,
                currentEntryInSnapshot = true,
                currentRetryAttempted = true,
            )
        )
    }

    @Test
    fun decodeError_isNotRecoverable() {
        val error = playbackError(
            code = PlaybackException.ERROR_CODE_DECODING_FAILED,
            cause = IllegalStateException("decoder failed"),
        )

        assertFalse(isRecoverablePlaybackError(error))
    }

    private fun playbackError(code: Int, cause: Throwable): PlaybackException {
        return PlaybackException("playback failed", cause, code)
    }

    private fun httpStatusError(code: Int): HttpDataSource.InvalidResponseCodeException {
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/music.wav"))
            .build()
        return HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            IOException("http status $code"),
            Collections.emptyMap(),
            dataSpec,
            ByteArray(0),
        )
    }
}
