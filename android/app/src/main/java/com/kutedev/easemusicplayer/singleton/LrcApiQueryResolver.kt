package com.kutedev.easemusicplayer.singleton

private val LRCAPI_AUDIO_EXTS = setOf(".wav", ".mp3", ".aac", ".flac", ".ogg", ".m4a")

data class LrcApiMetadataHint(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

data class LrcApiQuerySpec(
    val title: String,
    val artist: String,
    val album: String,
) {
    fun canQueryLyrics(): Boolean {
        return title.isNotBlank()
    }

    fun canQueryCover(): Boolean {
        return title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
    }
}

fun buildLrcApiQuerySpec(
    fallbackTitle: String?,
    fallbackPath: String?,
    metadataHint: LrcApiMetadataHint?,
): LrcApiQuerySpec {
    val title = normalizeMetadataField(metadataHint?.title).ifBlank {
        fallbackSearchTitle(fallbackTitle, fallbackPath)
    }
    return LrcApiQuerySpec(
        title = title,
        artist = normalizeMetadataField(metadataHint?.artist),
        album = normalizeMetadataField(metadataHint?.album),
    )
}

private fun normalizeMetadataField(value: String?): String {
    return value.orEmpty().trim()
}

private fun fallbackSearchTitle(title: String?, path: String?): String {
    val candidate = normalizeMetadataField(title).ifBlank {
        normalizeMetadataField(path?.substringAfterLast('/'))
    }
    return stripKnownAudioExtension(candidate)
}

private fun stripKnownAudioExtension(title: String): String {
    if (title.isBlank()) {
        return ""
    }
    val lowerTitle = title.lowercase()
    val extension = LRCAPI_AUDIO_EXTS.firstOrNull { lowerTitle.endsWith(it) } ?: return title
    return title.dropLast(extension.length).trim()
}
