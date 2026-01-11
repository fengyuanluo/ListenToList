package com.kutedev.easemusicplayer.core

import kotlin.math.max
import kotlin.math.min

object PlaybackCachePolicy {
    const val PREFETCH_SECONDS = 30

    private const val ASSUMED_BITRATE_KBPS = 640
    private const val MIN_PREFETCH_BYTES = 512L * 1024
    private const val MAX_PREFETCH_BYTES = 20L * 1024 * 1024

    fun prefetchBytesForSeconds(seconds: Int = PREFETCH_SECONDS): Long {
        val safeSeconds = max(seconds, 1)
        val bytesPerSecond = ASSUMED_BITRATE_KBPS * 1000L / 8L
        val estimated = bytesPerSecond * safeSeconds
        return min(MAX_PREFETCH_BYTES, max(MIN_PREFETCH_BYTES, estimated))
    }

    fun prefetchBytesByPercent(fileSizeBytes: Long, percent: Float = 0.1f): Long {
        if (fileSizeBytes <= 0) {
            return 0
        }
        val safePercent = percent.coerceIn(0.01f, 1f)
        val estimated = (fileSizeBytes * safePercent).toLong()
        return min(MAX_PREFETCH_BYTES, max(MIN_PREFETCH_BYTES, estimated))
    }
}
