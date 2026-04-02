package com.kutedev.easemusicplayer.utils

private val KNOWN_AUDIO_EXTS = setOf(".wav", ".mp3", ".aac", ".flac", ".ogg", ".m4a")

fun stripKnownAudioExtension(title: String): String {
    if (title.isBlank()) {
        return ""
    }
    val lowerTitle = title.lowercase()
    val extension = KNOWN_AUDIO_EXTS.firstOrNull { lowerTitle.endsWith(it) } ?: return title.trim()
    return title.dropLast(extension.length).trim()
}

fun fallbackMusicDisplayTitle(
    path: String?,
    storedTitle: String? = null,
): String {
    val fileName = path
        ?.substringAfterLast('/')
        ?.trim()
        .orEmpty()
    if (fileName.isNotBlank()) {
        return stripKnownAudioExtension(fileName)
    }
    return stripKnownAudioExtension(storedTitle.orEmpty())
}

fun resolveMusicDisplayTitle(
    metadataTitle: String?,
    path: String?,
    storedTitle: String? = null,
): String {
    val normalizedMetadataTitle = metadataTitle.orEmpty().trim()
    return if (normalizedMetadataTitle.isNotBlank()) {
        normalizedMetadataTitle
    } else {
        fallbackMusicDisplayTitle(
            path = path,
            storedTitle = storedTitle,
        )
    }
}

fun normalizeMusicDisplayArtist(artist: String?): String {
    return artist.orEmpty().trim()
}
