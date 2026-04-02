package com.kutedev.easemusicplayer.utils

import java.time.Duration
import uniffi.ease_client_backend.LyricLine

fun resolveLyricIndex(
    currentDuration: Duration,
    lyrics: List<LyricLine>,
): Int {
    return lyrics.indexOfLast { lyric -> lyric.duration <= currentDuration }
}
