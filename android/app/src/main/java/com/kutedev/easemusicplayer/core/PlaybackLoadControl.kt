package com.kutedev.easemusicplayer.core

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

object PlaybackLoadControl {
    private const val MIN_BUFFER_MS = 90_000
    private const val MAX_BUFFER_MS = 300_000
    private const val PLAYBACK_BUFFER_MS = 3_000
    private const val REBUFFER_MS = 8_000

    fun build(): LoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS
            )
            .build()
    }
}
