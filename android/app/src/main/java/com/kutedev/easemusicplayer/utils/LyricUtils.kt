package com.kutedev.easemusicplayer.utils

import java.time.Duration
import kotlin.math.abs
import uniffi.ease_client_backend.LyricLine

data class LyricViewportItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

fun resolveLyricIndex(
    currentDuration: Duration,
    lyrics: List<LyricLine>,
): Int {
    return lyrics.indexOfLast { lyric -> lyric.duration <= currentDuration }
}

fun resolveCenteredLyricIndex(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    visibleItems: List<LyricViewportItem>,
    lyricCount: Int,
): Int? {
    if (lyricCount <= 0) {
        return null
    }

    val viewportCenter = (viewportStartOffset + viewportEndOffset) / 2f
    return visibleItems
        .asSequence()
        .map { item ->
            val lyricIndex = item.index - 1
            val itemCenter = item.offset + item.size / 2f
            lyricIndex to abs(itemCenter - viewportCenter)
        }
        .filter { (lyricIndex, _) -> lyricIndex in 0 until lyricCount }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}
