package com.kutedev.easemusicplayer.core

import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DEFAULT_COVER_BASE64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.ArgUpdateMusicCover
import uniffi.ease_client_backend.ArgUpdateMusicDuration
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctsUpdateMusicCover
import uniffi.ease_client_backend.ctsUpdateMusicDuration
import uniffi.ease_client_schema.MusicId
import java.time.Duration


private fun extractCurrentTracksCover(player: Player): ByteArray? {
    player.currentTracks.groups.forEach { trackGroup ->
        (0 until trackGroup.length).forEach { i ->
            val format = trackGroup.getTrackFormat(i)
            val metadata = format.metadata
            if (metadata != null) {
                (0 until metadata.length()).forEach { j ->
                    val entry = metadata.get(j)
                    if (entry is ApicFrame) {
                        // ID3
                        return entry.pictureData
                    } else if (entry is PictureFrame) {
                        // FLAC
                        return entry.pictureData
                    }
                }
            }
        }
    }
    return null
}



private sealed class MusicOrMusicAbstract {
    data class VMusic(
        val v1: Music
    ) : MusicOrMusicAbstract()
    data class VMusicAbstract (
        val v1: MusicAbstract
    ) : MusicOrMusicAbstract() {}
}

data class BuildMediaContext(
    val bridge: Bridge,
    val scope: CoroutineScope
)

private fun buildMediaItem(cx: BuildMediaContext, music: MusicOrMusicAbstract): MediaItem {
    val cover = when(music) {
        is MusicOrMusicAbstract.VMusic -> music.v1.cover
        is MusicOrMusicAbstract.VMusicAbstract -> music.v1.cover
    }
    val meta = when(music) {
        is MusicOrMusicAbstract.VMusic -> music.v1.meta
        is MusicOrMusicAbstract.VMusicAbstract -> music.v1.meta
    }

    val coverURI = if (cover != null) {
        null
    } else {
        DEFAULT_COVER_BASE64.toUri()
    }

    val mediaItem = MediaItem.Builder()
        .setMediaId(meta.id.value.toString())
        .setUri(buildPlaybackMusicUri(meta.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(meta.title)
                .setArtworkUri(coverURI)
                .build()
        )
        .build()

    return mediaItem
}

fun buildMediaItem(cx: BuildMediaContext, music: Music): MediaItem {
    return buildMediaItem(cx, MusicOrMusicAbstract.VMusic(music))
}
fun buildMediaItem(cx: BuildMediaContext, music: MusicAbstract): MediaItem {
    return buildMediaItem(cx, MusicOrMusicAbstract.VMusicAbstract(music))
}

private fun playUtil(cx: BuildMediaContext, music: MusicOrMusicAbstract, player: Player) {
    val mediaItem = buildMediaItem(cx, music)
    player.stop()
    player.setMediaItem(mediaItem)
    player.prepare()
    player.play()
}
fun playUtil(cx: BuildMediaContext, music: Music, player: Player) {
    playUtil(cx, MusicOrMusicAbstract.VMusic(music), player)
}
fun playUtil(cx: BuildMediaContext, music: MusicAbstract, player: Player) {
    playUtil(cx, MusicOrMusicAbstract.VMusicAbstract(music), player)
}

fun syncMetadataUtil(
    scope: CoroutineScope,
    bridge: Bridge,
    player: Player,
    onUpdated: (MusicId) -> Unit = {},
    onFinished: (() -> Unit)? = null
): Job? {
    if (!player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
        onFinished?.invoke()
        return null
    }
    val mediaItem = player.currentMediaItem
    if (mediaItem == null || !mediaItem.mediaId.isDigitsOnly()) {
        onFinished?.invoke()
        return null
    }

    val id = MusicId(mediaItem.mediaId.toLong())
    val expectedMediaId = mediaItem.mediaId
    return scope.launch {
        try {
            var coverData: ByteArray? = null
            var playbackStale = false
            var durationMS = withContext(Dispatchers.Main.immediate) {
                if (player.currentMediaItem?.mediaId != expectedMediaId) {
                    playbackStale = true
                    TIME_UNSET
                } else {
                    coverData = extractCurrentTracksCover(player)
                    player.duration
                }
            }
            if (playbackStale) {
                return@launch
            }
            var attempt = 0
            while (durationMS == TIME_UNSET && attempt < 10) {
                delay(500)
                durationMS = withContext(Dispatchers.Main.immediate) {
                    if (player.currentMediaItem?.mediaId != expectedMediaId) {
                        playbackStale = true
                        TIME_UNSET
                    } else {
                        if (coverData == null) {
                            coverData = extractCurrentTracksCover(player)
                        }
                        player.duration
                    }
                }
                if (playbackStale) {
                    return@launch
                }
                attempt += 1
            }
            if (durationMS == TIME_UNSET) {
                return@launch
            }

            val music = bridge.run { backend -> ctGetMusic(backend, id) }
            val duration = Duration.ofMillis(durationMS)
            var updated = false

            if (music?.meta?.duration != duration) {
                bridge.runSync { backend -> ctsUpdateMusicDuration(backend, ArgUpdateMusicDuration(
                    id = id,
                    duration = duration
                ))}
                updated = true
            }
            if (music?.cover == null && coverData != null) {
                bridge.runSync { backend -> ctsUpdateMusicCover(backend, ArgUpdateMusicCover(
                    id = id,
                    cover = coverData
                )) }
                updated = true
            }
            if (updated) {
                onUpdated(id)
            }
        } finally {
            onFinished?.invoke()
        }
    }
}
