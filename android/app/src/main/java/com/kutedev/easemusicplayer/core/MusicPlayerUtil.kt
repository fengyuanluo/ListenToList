package com.kutedev.easemusicplayer.core

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.text.isDigitsOnly
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.PlaybackQueueEntry
import com.kutedev.easemusicplayer.singleton.PlaybackQueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import uniffi.ease_client_backend.ArgUpdateMusicCover
import uniffi.ease_client_backend.ArgUpdateMusicDuration
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctsUpdateMusicCover
import uniffi.ease_client_backend.ctsUpdateMusicDuration
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import java.time.Duration
import java.util.Arrays

private const val APPLICATION_PACKAGE = "com.kutedev.easemusicplayer"

private val DEFAULT_COVER_ARTWORK_URI: Uri = Uri.Builder()
    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
    .authority(APPLICATION_PACKAGE)
    .appendPath("drawable")
    .appendPath("cover_default_image")
    .build()

internal fun extractCurrentTracksCover(player: Player): ByteArray? {
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

internal data class PlaybackQueuePlan(
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
    val repeatMode: Int,
)

data class ProbedMusicMetadata(
    val duration: Duration?,
    val cover: ByteArray?,
    val title: String?,
    val artist: String?,
    val album: String?,
)

internal fun buildPlaybackNotificationMediaMetadata(
    baseMetadata: MediaMetadata,
    probedMetadata: ProbedMusicMetadata?,
    artworkData: ByteArray?,
): MediaMetadata {
    return baseMetadata.buildUpon()
        .apply {
            probedMetadata?.title?.takeIf { it.isNotBlank() }?.let(::setTitle)
            probedMetadata?.artist?.takeIf { it.isNotBlank() }?.let(::setArtist)
            probedMetadata?.album?.takeIf { it.isNotBlank() }?.let(::setAlbumTitle)
            artworkData?.takeIf { it.isNotEmpty() }?.let { setArtworkData(it, null) }
        }
        .build()
}

internal fun playbackNotificationMetadataEquals(
    left: MediaMetadata,
    right: MediaMetadata,
): Boolean {
    return left.title == right.title &&
        left.artist == right.artist &&
        left.albumTitle == right.albumTitle &&
        left.artworkUri == right.artworkUri &&
        Arrays.equals(left.artworkData, right.artworkData)
}

internal fun repeatModeFor(playMode: PlayMode): Int {
    return when (playMode) {
        PlayMode.SINGLE, PlayMode.LIST -> Player.REPEAT_MODE_OFF
        PlayMode.SINGLE_LOOP -> Player.REPEAT_MODE_ONE
        PlayMode.LIST_LOOP -> Player.REPEAT_MODE_ALL
    }
}

private fun buildMediaItemInternal(
    music: MusicOrMusicAbstract,
    mediaIdOverride: String? = null,
): MediaItem {
    val cover = when(music) {
        is MusicOrMusicAbstract.VMusic -> music.v1.cover
        is MusicOrMusicAbstract.VMusicAbstract -> music.v1.cover
    }
    val meta = when(music) {
        is MusicOrMusicAbstract.VMusic -> music.v1.meta
        is MusicOrMusicAbstract.VMusicAbstract -> music.v1.meta
    }

    val coverURI = if (cover != null) null else DEFAULT_COVER_ARTWORK_URI

    val mediaMetadata = MediaMetadata.Builder()
        .setTitle(meta.title)
        .setArtworkUri(coverURI)
        .build()

    return MediaItem.Builder()
        .setMediaId(mediaIdOverride ?: meta.id.value.toString())
        .setUri(buildPlaybackMusicUri(meta.id))
        .setMediaMetadata(mediaMetadata)
        .build()
}

fun buildMediaItem(cx: BuildMediaContext, music: Music): MediaItem {
    return buildMediaItemInternal(MusicOrMusicAbstract.VMusic(music))
}
fun buildMediaItem(cx: BuildMediaContext, music: MusicAbstract): MediaItem {
    return buildMediaItemInternal(MusicOrMusicAbstract.VMusicAbstract(music))
}

private fun buildQueueMediaItem(entry: PlaybackQueueEntry): MediaItem {
    return buildMediaItemInternal(
        MusicOrMusicAbstract.VMusicAbstract(entry.musicAbstract),
        mediaIdOverride = entry.queueEntryId,
    )
}

internal fun buildPlaybackQueuePlan(
    playlist: Playlist,
    targetId: MusicId,
    playMode: PlayMode,
): PlaybackQueuePlan? {
    val targetIndex = playlist.musics.indexOfFirst { it.meta.id == targetId }
    if (targetIndex < 0) {
        return null
    }

    val queueMusics = when (playMode) {
        PlayMode.SINGLE, PlayMode.SINGLE_LOOP -> listOf(playlist.musics[targetIndex])
        PlayMode.LIST, PlayMode.LIST_LOOP -> playlist.musics
    }
    val repeatMode = repeatModeFor(playMode)
    val startIndex = when (playMode) {
        PlayMode.SINGLE, PlayMode.SINGLE_LOOP -> 0
        PlayMode.LIST, PlayMode.LIST_LOOP -> targetIndex
    }

    return PlaybackQueuePlan(
        mediaItems = queueMusics.map { buildMediaItemInternal(MusicOrMusicAbstract.VMusicAbstract(it)) },
        startIndex = startIndex,
        repeatMode = repeatMode,
    )
}

internal fun buildPlaybackQueuePlan(
    snapshot: PlaybackQueueSnapshot,
    targetQueueEntryId: String,
    playMode: PlayMode,
): PlaybackQueuePlan? {
    val targetIndex = snapshot.entries.indexOfFirst { it.queueEntryId == targetQueueEntryId }
    if (targetIndex < 0) {
        return null
    }

    val queueEntries = when (playMode) {
        PlayMode.SINGLE, PlayMode.SINGLE_LOOP -> listOf(snapshot.entries[targetIndex])
        PlayMode.LIST, PlayMode.LIST_LOOP -> snapshot.entries
    }
    val repeatMode = repeatModeFor(playMode)
    val startIndex = when (playMode) {
        PlayMode.SINGLE, PlayMode.SINGLE_LOOP -> 0
        PlayMode.LIST, PlayMode.LIST_LOOP -> targetIndex
    }

    return PlaybackQueuePlan(
        mediaItems = queueEntries.map(::buildQueueMediaItem),
        startIndex = startIndex,
        repeatMode = repeatMode,
    )
}

fun playQueueUtil(
    playlist: Playlist,
    targetId: MusicId,
    playMode: PlayMode,
    player: Player,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
) {
    val plan = buildPlaybackQueuePlan(playlist, targetId, playMode) ?: return
    player.repeatMode = plan.repeatMode
    player.stop()
    player.clearMediaItems()
    player.setMediaItems(plan.mediaItems, plan.startIndex, startPositionMs.coerceAtLeast(0L))
    player.prepare()
    if (playWhenReady) {
        player.play()
    } else {
        player.pause()
    }
}

fun playQueueUtil(
    snapshot: PlaybackQueueSnapshot,
    targetQueueEntryId: String,
    playMode: PlayMode,
    player: Player,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true,
) {
    val plan = buildPlaybackQueuePlan(snapshot, targetQueueEntryId, playMode) ?: return
    player.repeatMode = plan.repeatMode
    player.stop()
    player.clearMediaItems()
    player.setMediaItems(plan.mediaItems, plan.startIndex, startPositionMs.coerceAtLeast(0L))
    player.prepare()
    if (playWhenReady) {
        player.play()
    } else {
        player.pause()
    }
}

private fun playUtil(cx: BuildMediaContext, music: MusicOrMusicAbstract, player: Player) {
    val mediaItem = buildMediaItemInternal(music)
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

private fun extractDuration(metadataRetriever: MediaMetadataRetriever): Duration? {
    val durationMs = metadataRetriever
        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull()
        ?: return null
    return Duration.ofMillis(durationMs)
}

private fun extractMetadataText(
    metadataRetriever: MediaMetadataRetriever,
    keyCode: Int,
): String? {
    return metadataRetriever
        .extractMetadata(keyCode)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

suspend fun probeMusicMetadataDirectly(
    bridge: Bridge,
    musicId: MusicId,
): ProbedMusicMetadata? = withContext(Dispatchers.IO) {
    val resolved = runCatching {
        resolveMusicPlaybackSourceWithBridge(bridge, musicId)
    }.getOrNull() ?: return@withContext null

    val metadataRetriever = MediaMetadataRetriever()
    try {
            when (resolved) {
                is ResolvedMusicPlaybackSource.DirectHttp -> {
                    metadataRetriever.setDataSource(
                        resolved.url,
                        resolved.headers.associate { it.name to it.value },
                    )
                }
                is ResolvedMusicPlaybackSource.LocalFile -> {
                    metadataRetriever.setDataSource(resolved.absolutePath)
                }
                is ResolvedMusicPlaybackSource.DownloadedFile -> {
                    metadataRetriever.setDataSource(resolved.absolutePath)
                }
                is ResolvedMusicPlaybackSource.ContentUri -> {
                    return@withContext null
                }
                ResolvedMusicPlaybackSource.StreamFallback -> {
                    return@withContext null
                }
            }
        val duration = extractDuration(metadataRetriever)
        val cover = metadataRetriever.embeddedPicture
        val title = extractMetadataText(metadataRetriever, MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = extractMetadataText(metadataRetriever, MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album = extractMetadataText(metadataRetriever, MediaMetadataRetriever.METADATA_KEY_ALBUM)
        if (duration == null && cover == null && title == null && artist == null && album == null) {
            return@withContext null
        }
        ProbedMusicMetadata(
            duration = duration,
            cover = cover,
            title = title,
            artist = artist,
            album = album,
        )
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { metadataRetriever.release() }
    }
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
    if (mediaItem == null) {
        onFinished?.invoke()
        return null
    }

    val id = resolveMusicIdFromMediaItem(mediaItem)
    if (id == null) {
        onFinished?.invoke()
        return null
    }
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

fun resolveMusicIdFromMediaItem(mediaItem: MediaItem?): MusicId? {
    if (mediaItem == null) {
        return null
    }
    mediaItem.localConfiguration?.uri?.let(::parsePlaybackMusicId)?.let { return it }
    if (mediaItem.mediaId.isDigitsOnly()) {
        return MusicId(mediaItem.mediaId.toLong())
    }
    return null
}
