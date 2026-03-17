package com.kutedev.easemusicplayer.singleton

import android.content.Context
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.MusicMeta
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_backend.PlaylistAbstract
import uniffi.ease_client_backend.PlaylistMeta
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackRuntimeKernelTest {
    @Test
    fun buildPlaylistSnapshot_usesQueueEntryIdsAndResolvesRequestedCurrentEntry() {
        val fixture = createFixture()
        val playlistId = PlaylistId(7L)
        val playlist = buildPlaylist(playlistId, listOf(41L, 42L, 43L))

        val snapshot = fixture.kernel.buildPlaylistSnapshot(
            playlist = playlist,
            requestedQueueEntryId = buildPlaylistQueueEntryId(playlistId, MusicId(42L)),
        )

        assertNotNull(snapshot)
        assertEquals(
            listOf(
                buildPlaylistQueueEntryId(playlistId, MusicId(41L)),
                buildPlaylistQueueEntryId(playlistId, MusicId(42L)),
                buildPlaylistQueueEntryId(playlistId, MusicId(43L)),
            ),
            snapshot!!.entries.map { it.queueEntryId },
        )
        assertEquals(
            buildPlaylistQueueEntryId(playlistId, MusicId(42L)),
            snapshot.currentQueueEntryId,
        )
    }

    @Test
    fun persistCurrentSession_savesQueueOverrideAndPlaybackFlags() {
        val fixture = createFixture()
        val playlistId = PlaylistId(1L)
        val context = PlaybackContext(
            type = PlaybackContextType.USER_PLAYLIST,
            playlistId = playlistId,
        )
        val first = buildEntry(11L, context, order = 0U)
        val second = buildEntry(12L, context, order = 1U)
        fixture.playerRepository.setPlaybackSession(
            music = buildMusic(11L, order = listOf(0U)),
            queueSnapshot = PlaybackQueueSnapshot(
                context = context,
                entries = listOf(first, second),
                currentQueueEntryId = first.queueEntryId,
            ),
            currentQueueEntryId = first.queueEntryId,
        )

        fixture.kernel.persistCurrentSession(
            positionMs = 4_567L,
            playWhenReady = false,
            currentQueueEntryIdOverride = second.queueEntryId,
        )

        val persisted = fixture.store.load()
        assertNotNull(persisted)
        assertEquals(second.queueEntryId, persisted!!.currentQueueEntryId)
        assertEquals(4_567L, persisted.positionMs)
        assertFalse(persisted.playWhenReady)
        assertEquals(PlayMode.SINGLE.name, persisted.playMode)
    }

    private fun createFixture(): TestFixture {
        val appContext = RuntimeEnvironment.getApplication().applicationContext
        appContext.getSharedPreferences("playback_session", Context.MODE_PRIVATE).edit().clear().apply()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val toastRepository = ToastRepository(scope)
        val bridge = Bridge(appContext, toastRepository)
        val playerRepository = PlayerRepository(bridge, scope)
        val store = PlaybackSessionStore(appContext)
        return TestFixture(
            playerRepository = playerRepository,
            store = store,
            kernel = PlaybackRuntimeKernel(
                playerRepository = playerRepository,
                playbackSessionStore = store,
                bridge = bridge,
            ),
        )
    }

    private fun buildPlaylist(
        playlistId: PlaylistId,
        ids: List<Long>,
    ): Playlist {
        val musics = ids.mapIndexed { index, id ->
            MusicAbstract(
                meta = MusicMeta(
                    id = MusicId(id),
                    title = "track-$id",
                    duration = Duration.ofSeconds(index.toLong() + 1L),
                    order = listOf(index.toUInt()),
                ),
                cover = null,
            )
        }
        return Playlist(
            abstr = PlaylistAbstract(
                meta = PlaylistMeta(
                    id = playlistId,
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

    private fun buildEntry(
        id: Long,
        context: PlaybackContext,
        order: UInt,
    ): PlaybackQueueEntry {
        val musicAbstract = MusicAbstract(
            meta = MusicMeta(
                id = MusicId(id),
                title = "track-$id",
                duration = Duration.ofSeconds(1),
                order = listOf(order),
            ),
            cover = null,
        )
        return PlaybackQueueEntry(
            queueEntryId = buildPlaylistQueueEntryId(context.playlistId!!, musicAbstract.meta.id),
            musicId = musicAbstract.meta.id,
            musicAbstract = musicAbstract,
            sourceContext = context,
        )
    }

    private fun buildMusic(
        id: Long,
        order: List<UInt>,
    ): Music {
        return Music(
            meta = MusicMeta(
                id = MusicId(id),
                title = "track-$id",
                duration = Duration.ofSeconds(1),
                order = order,
            ),
            loc = StorageEntryLoc(
                storageId = StorageId(7L),
                path = "/music/$id.mp3",
            ),
            cover = null,
            lyric = null,
        )
    }

    private data class TestFixture(
        val playerRepository: PlayerRepository,
        val store: PlaybackSessionStore,
        val kernel: PlaybackRuntimeKernel,
    )
}
