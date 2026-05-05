package com.kutedev.easemusicplayer.core

import android.net.Uri
import uniffi.ease_client_schema.MusicId

private const val PLAYBACK_URI_SCHEME = "ease"
private const val PLAYBACK_URI_AUTHORITY = "data"
private const val PLAYBACK_URI_MUSIC_PARAM = "music"
private const val PLAYBACK_CACHE_KEY_PREFIX = "music:"

fun buildPlaybackMusicUri(musicId: MusicId): Uri {
    return buildPlaybackMusicUri(musicId.value)
}

fun buildPlaybackMusicUri(musicId: Long): Uri {
    return Uri.parse(
        "$PLAYBACK_URI_SCHEME://$PLAYBACK_URI_AUTHORITY?$PLAYBACK_URI_MUSIC_PARAM=$musicId"
    )
}

fun parsePlaybackMusicId(uri: Uri): MusicId? {
    return parsePlaybackMusicIdValue(uri)?.let(::MusicId)
}

fun parsePlaybackMusicIdValue(uri: Uri): Long? {
    if (uri.scheme != PLAYBACK_URI_SCHEME || uri.authority != PLAYBACK_URI_AUTHORITY) {
        return null
    }
    val raw = uri.getQueryParameter(PLAYBACK_URI_MUSIC_PARAM)
    return raw?.toLongOrNull()
}

fun buildPlaybackMusicCacheKey(musicId: MusicId): String {
    return buildPlaybackMusicCacheKey(musicId.value)
}

fun buildPlaybackMusicCacheKey(musicId: Long): String {
    return "$PLAYBACK_CACHE_KEY_PREFIX$musicId"
}

fun buildPlaybackMusicCacheKey(uri: Uri): String? {
    return parsePlaybackMusicIdValue(uri)?.let(::buildPlaybackMusicCacheKey)
}
