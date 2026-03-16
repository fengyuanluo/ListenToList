package com.kutedev.easemusicplayer.singleton

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.MusicMeta
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlayerRepositoryTest {
    @Test
    fun setPlaybackSession_keepsSnapshotCurrentQueueEntryIdAligned() {
        val repository = createRepository()
        val context = PlaybackContext(
            type = PlaybackContextType.USER_PLAYLIST,
            playlistId = PlaylistId(1L),
        )
        val first = buildEntry(11L, context, order = 0U)
        val second = buildEntry(12L, context, order = 1U)
        val snapshot = PlaybackQueueSnapshot(
            context = context,
            entries = listOf(first, second),
            currentQueueEntryId = first.queueEntryId,
        )

        repository.setPlaybackSession(
            music = buildMusic(12L, listOf(1U)),
            queueSnapshot = snapshot,
            currentQueueEntryId = second.queueEntryId,
        )

        assertEquals(second.queueEntryId, repository.currentQueueEntryId.value)
        assertEquals(second.queueEntryId, repository.playbackQueue.value?.currentQueueEntryId)
    }

    @Test
    fun updateCurrentQueueEntry_updatesSnapshotCursorTogether() {
        val repository = createRepository()
        val context = PlaybackContext(
            type = PlaybackContextType.FOLDER,
            storageId = StorageId(7L),
            folderPath = "/music",
        )
        val first = buildEntry(21L, context, order = 0U)
        val second = buildEntry(22L, context, order = 1U)
        repository.setPlaybackSession(
            music = buildMusic(21L, listOf(0U)),
            queueSnapshot = PlaybackQueueSnapshot(
                context = context,
                entries = listOf(first, second),
                currentQueueEntryId = first.queueEntryId,
            ),
            currentQueueEntryId = first.queueEntryId,
        )

        repository.updateCurrentQueueEntry(
            queueEntryId = second.queueEntryId,
            music = buildMusic(22L, listOf(1U)),
        )

        assertEquals(second.queueEntryId, repository.currentQueueEntryId.value)
        assertEquals(second.queueEntryId, repository.playbackQueue.value?.currentQueueEntryId)
        assertEquals(MusicId(22L), repository.music.value?.meta?.id)
    }

    private fun createRepository(): PlayerRepository {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val toastRepository = ToastRepository(scope)
        val bridge = Bridge(RuntimeEnvironment.getApplication().applicationContext, toastRepository)
        return PlayerRepository(bridge, scope)
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
        val queueEntryId = when (context.type) {
            PlaybackContextType.USER_PLAYLIST -> buildPlaylistQueueEntryId(context.playlistId!!, musicAbstract.meta.id)
            PlaybackContextType.FOLDER -> buildFolderQueueEntryId(context.storageId!!, context.folderPath!!, musicAbstract.meta.id, order.toInt())
        }
        return PlaybackQueueEntry(
            queueEntryId = queueEntryId,
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
}
