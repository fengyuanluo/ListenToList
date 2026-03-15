package com.kutedev.easemusicplayer.core

import androidx.media3.common.Player
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.MusicMeta
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.PlaylistAbstract
import uniffi.ease_client_backend.PlaylistMeta
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MusicPlayerUtilTest {
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
}
