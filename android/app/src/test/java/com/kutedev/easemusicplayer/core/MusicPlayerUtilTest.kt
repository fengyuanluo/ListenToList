package com.kutedev.easemusicplayer.core

import android.content.ContentResolver
import androidx.media3.common.Player
import com.kutedev.easemusicplayer.singleton.PlaybackContext
import com.kutedev.easemusicplayer.singleton.PlaybackContextType
import com.kutedev.easemusicplayer.singleton.PlaybackQueueEntry
import com.kutedev.easemusicplayer.singleton.PlaybackQueueSnapshot
import com.kutedev.easemusicplayer.singleton.buildPlaylistQueueEntryId
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.MusicMeta
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.PlaylistAbstract
import uniffi.ease_client_backend.PlaylistMeta
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicPlayerUtilTest {
    @Test
    fun buildPlaybackNotificationMediaMetadata_prefersProbedFieldsAndArtworkData() {
        val baseMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle("stored-title")
            .setArtworkUri(DEFAULT_TEST_ARTWORK_URI)
            .build()

        val updated = buildPlaybackNotificationMediaMetadata(
            baseMetadata = baseMetadata,
            probedMetadata = ProbedMusicMetadata(
                duration = Duration.ofSeconds(123),
                cover = null,
                title = "probed-title",
                artist = "probed-artist",
                album = "probed-album",
            ),
            artworkData = byteArrayOf(1, 2, 3),
        )

        assertEquals("probed-title", updated.title)
        assertEquals("probed-artist", updated.artist)
        assertEquals("probed-album", updated.albumTitle)
        assertTrue(updated.artworkData.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(DEFAULT_TEST_ARTWORK_URI, updated.artworkUri)
    }

    @Test
    fun playbackNotificationMetadataEquals_detectsRelevantChanges() {
        val left = androidx.media3.common.MediaMetadata.Builder()
            .setTitle("title")
            .setArtist("artist")
            .setArtworkData(byteArrayOf(7, 8), null)
            .build()
        val same = androidx.media3.common.MediaMetadata.Builder()
            .setTitle("title")
            .setArtist("artist")
            .setArtworkData(byteArrayOf(7, 8), null)
            .build()
        val different = androidx.media3.common.MediaMetadata.Builder()
            .setTitle("title")
            .setArtist("artist-2")
            .setArtworkData(byteArrayOf(7, 8), null)
            .build()

        assertTrue(playbackNotificationMetadataEquals(left, same))
        assertFalse(playbackNotificationMetadataEquals(left, different))
    }

    @Test
    fun buildPlaybackQueuePlan_singleModeOnlyQueuesCurrentTrack() {
        val playlist = testPlaylist(listOf(11L, 12L, 13L))

        val plan = buildPlaybackQueuePlan(
            playlist = playlist,
            targetId = MusicId(12L),
            playMode = PlayMode.SINGLE,
        )

        assertNotNull(plan)
        assertEquals(listOf("12"), plan!!.mediaItems.map { it.mediaId })
        assertEquals(0, plan.startIndex)
        assertEquals(Player.REPEAT_MODE_OFF, plan.repeatMode)
    }

    @Test
    fun buildPlaybackQueuePlan_listLoopQueuesWholePlaylistAndKeepsStartIndex() {
        val playlist = testPlaylist(listOf(21L, 22L, 23L))

        val plan = buildPlaybackQueuePlan(
            playlist = playlist,
            targetId = MusicId(22L),
            playMode = PlayMode.LIST_LOOP,
        )

        assertNotNull(plan)
        assertEquals(listOf("21", "22", "23"), plan!!.mediaItems.map { it.mediaId })
        assertEquals(1, plan.startIndex)
        assertEquals(Player.REPEAT_MODE_ALL, plan.repeatMode)
    }

    @Test
    fun buildPlaybackQueuePlan_returnsNullWhenTargetMissing() {
        val playlist = testPlaylist(listOf(31L, 32L))

        val plan = buildPlaybackQueuePlan(
            playlist = playlist,
            targetId = MusicId(99L),
            playMode = PlayMode.LIST,
        )

        assertNull(plan)
    }

    @Test
    fun buildPlaybackQueuePlan_snapshotUsesQueueEntryIdentity() {
        val playlistId = PlaylistId(7L)
        val musics = listOf(41L, 42L, 43L).mapIndexed { index, id ->
            MusicAbstract(
                meta = MusicMeta(
                    id = MusicId(id),
                    title = "track-$id",
                    duration = Duration.ofSeconds(index.toLong() + 1),
                    order = emptyList(),
                ),
                cover = null,
            )
        }
        val snapshot = PlaybackQueueSnapshot(
            context = PlaybackContext(
                type = PlaybackContextType.USER_PLAYLIST,
                playlistId = playlistId,
            ),
            entries = musics.map { music ->
                PlaybackQueueEntry(
                    queueEntryId = buildPlaylistQueueEntryId(playlistId, music.meta.id),
                    musicId = music.meta.id,
                    musicAbstract = music,
                    sourceContext = PlaybackContext(
                        type = PlaybackContextType.USER_PLAYLIST,
                        playlistId = playlistId,
                    ),
                )
            },
            currentQueueEntryId = buildPlaylistQueueEntryId(playlistId, MusicId(42L)),
        )

        val plan = buildPlaybackQueuePlan(
            snapshot = snapshot,
            targetQueueEntryId = snapshot.currentQueueEntryId,
            playMode = PlayMode.LIST_LOOP,
        )

        assertNotNull(plan)
        assertEquals(
            listOf(
                buildPlaylistQueueEntryId(playlistId, MusicId(41L)),
                buildPlaylistQueueEntryId(playlistId, MusicId(42L)),
                buildPlaylistQueueEntryId(playlistId, MusicId(43L)),
            ),
            plan!!.mediaItems.map { it.mediaId },
        )
        assertEquals(1, plan.startIndex)
        assertEquals(Player.REPEAT_MODE_ALL, plan.repeatMode)
        assertEquals(
            MusicId(42L),
            resolveMusicIdFromMediaItem(plan.mediaItems[1]),
        )
        assertEquals("music:42", plan.mediaItems[1].localConfiguration?.customCacheKey)
    }

    @Test
    fun buildPlaybackQueuePlan_missingCoverUsesCompactResourceArtworkUri() {
        val playlist = testPlaylist(listOf(51L, 52L))

        val plan = buildPlaybackQueuePlan(
            playlist = playlist,
            targetId = MusicId(51L),
            playMode = PlayMode.LIST,
        )

        val artworkUri = plan!!.mediaItems.first().mediaMetadata.artworkUri
        assertNotNull(artworkUri)
        assertEquals(ContentResolver.SCHEME_ANDROID_RESOURCE, artworkUri!!.scheme)
        assertEquals("com.kutedev.easemusicplayer", artworkUri.authority)
        assertEquals(listOf("drawable", "cover_default_image"), artworkUri.pathSegments)
        assertTrue(artworkUri.toString().length < 128)
        assertFalse(artworkUri.toString().startsWith("data:image"))
    }

    @Test
    fun buildPlaybackQueuePlan_existingCoverDoesNotInjectFallbackArtworkUri() {
        val playlist = Playlist(
            abstr = PlaylistAbstract(
                meta = PlaylistMeta(
                    id = PlaylistId(2L),
                    title = "playlist",
                    cover = null,
                    showCover = null,
                    createdTime = Duration.ZERO,
                    order = emptyList(),
                ),
                musicCount = 1uL,
                duration = null,
            ),
            musics = listOf(
                MusicAbstract(
                    meta = MusicMeta(
                        id = MusicId(61L),
                        title = "track-61",
                        duration = Duration.ofSeconds(61),
                        order = emptyList(),
                    ),
                    cover = DataSourceKey.Cover(MusicId(61L)),
                ),
            ),
        )

        val plan = buildPlaybackQueuePlan(
            playlist = playlist,
            targetId = MusicId(61L),
            playMode = PlayMode.LIST,
        )

        assertNull(plan!!.mediaItems.first().mediaMetadata.artworkUri)
    }

    private fun testPlaylist(ids: List<Long>): Playlist {
        val musics = ids.mapIndexed { index, id ->
            MusicAbstract(
                meta = MusicMeta(
                    id = MusicId(id),
                    title = "track-$id",
                    duration = Duration.ofSeconds(index.toLong() + 1),
                    order = emptyList(),
                ),
                cover = null,
            )
        }
        return Playlist(
            abstr = PlaylistAbstract(
                meta = PlaylistMeta(
                    id = PlaylistId(1L),
                    title = "playlist",
                    cover = null,
                    showCover = null,
                    createdTime = Duration.ZERO,
                    order = emptyList(),
                ),
                musicCount = musics.size.toULong(),
                duration = null,
            ),
            musics = musics,
        )
    }

    companion object {
        private val DEFAULT_TEST_ARTWORK_URI = android.net.Uri.parse(
            "android.resource://com.kutedev.easemusicplayer/drawable/cover_default_image",
        )
    }
}
