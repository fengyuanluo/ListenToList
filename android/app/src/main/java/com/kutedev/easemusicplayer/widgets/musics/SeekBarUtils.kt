package com.kutedev.easemusicplayer.widgets.musics

import com.kutedev.easemusicplayer.utils.formatDuration
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToLong

internal const val PLAYER_PROGRESS_SLIDER_TEST_TAG = "player_progress_slider"
internal const val PLAYER_PROGRESS_CURRENT_DURATION_TEST_TAG = "player_progress_current_duration"
internal const val PLAYER_PROGRESS_TOTAL_DURATION_TEST_TAG = "player_progress_total_duration"
internal const val MINI_PLAYER_SEEK_BAR_TEST_TAG = "mini_player_seek_bar"

internal const val OPTIMISTIC_SEEK_SETTLE_TOLERANCE_MS = 750L
internal const val OPTIMISTIC_SEEK_TIMEOUT_MS = 1500L

internal fun formatProgressDuration(durationMs: ULong?): String {
    return durationMs?.let { value ->
        formatDuration(Duration.ofMillis(value.toLong()))
    } ?: "--:--:--"
}

internal fun resolveSeekRatio(
    positionMs: ULong,
    totalDurationMs: ULong?,
): Float {
    val total = totalDurationMs?.takeIf { it > 0uL } ?: return 0f
    return (positionMs.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun resolveSeekPositionFromOffset(
    offsetPx: Float,
    widthPx: Int,
    totalDurationMs: ULong?,
): ULong {
    val total = totalDurationMs?.takeIf { it > 0uL } ?: return 0uL
    if (!offsetPx.isFinite() || widthPx <= 0) {
        return 0uL
    }
    val ratio = (offsetPx / widthPx.toFloat()).coerceIn(0f, 1f)
    return resolveSeekPositionFromRatio(ratio, total)
}

internal fun resolveSeekPositionFromRatio(
    ratio: Float,
    totalDurationMs: ULong,
): ULong {
    if (!ratio.isFinite() || totalDurationMs == 0uL) {
        return 0uL
    }
    return (ratio.coerceIn(0f, 1f) * totalDurationMs.toDouble())
        .roundToLong()
        .coerceAtLeast(0L)
        .toULong()
}

internal fun resolveSeekPositionFromSliderValue(
    value: Float,
    totalDurationMs: ULong?,
): ULong {
    val total = totalDurationMs?.takeIf { it > 0uL } ?: return 0uL
    if (!value.isFinite()) {
        return 0uL
    }
    return value
        .coerceIn(0f, total.toFloat())
        .roundToLong()
        .coerceAtLeast(0L)
        .toULong()
}

internal fun shouldClearOptimisticSeek(
    reportedPositionMs: ULong,
    optimisticPositionMs: ULong,
    toleranceMs: Long = OPTIMISTIC_SEEK_SETTLE_TOLERANCE_MS,
): Boolean {
    if (toleranceMs < 0L) {
        return true
    }
    return abs(reportedPositionMs.toLong() - optimisticPositionMs.toLong()) <= toleranceMs
}
