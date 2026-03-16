package com.kutedev.easemusicplayer.singleton

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.MusicMeta
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlayMode
import uniffi.ease_client_schema.StorageId
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackSessionStoreTest {
    @Test
    fun saveAndLoad_roundTripsFolderQueueSession() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences("playback_session", Context.MODE_PRIVATE).edit().clear().apply()
        val store = PlaybackSessionStore(context)
        val snapshot = PlaybackQueueSnapshot(
            context = PlaybackContext(
                type = PlaybackContextType.FOLDER,
                storageId = StorageId(5L),
                folderPath = "/music",
            ),
            entries = listOf(
                PlaybackQueueEntry(
                    queueEntryId = buildFolderQueueEntryId(StorageId(5L), "/music", MusicId(11L), 0),
                    musicId = MusicId(11L),
                    musicAbstract = testMusic(11L),
                    sourceContext = PlaybackContext(
                        type = PlaybackContextType.FOLDER,
                        storageId = StorageId(5L),
                        folderPath = "/music",
                    ),
                ),
                PlaybackQueueEntry(
                    queueEntryId = buildFolderQueueEntryId(StorageId(5L), "/music", MusicId(12L), 1),
                    musicId = MusicId(12L),
                    musicAbstract = testMusic(12L),
                    sourceContext = PlaybackContext(
                        type = PlaybackContextType.FOLDER,
                        storageId = StorageId(5L),
                        folderPath = "/music",
                    ),
                ),
            ),
            currentQueueEntryId = buildFolderQueueEntryId(StorageId(5L), "/music", MusicId(12L), 1),
        )

        store.save(
            snapshot = snapshot,
            positionMs = 12_345L,
            playWhenReady = true,
            playMode = PlayMode.LIST_LOOP,
        )

        val restored = store.load()
        assertNotNull(restored)
        assertEquals(PlaybackContextType.FOLDER.name, restored!!.contextType)
        assertEquals(5L, restored.storageId)
        assertEquals("/music", restored.folderPath)
        assertEquals(snapshot.currentQueueEntryId, restored.currentQueueEntryId)
        assertEquals(12_345L, restored.positionMs)
        assertEquals(true, restored.playWhenReady)
        assertEquals(PlayMode.LIST_LOOP.name, restored.playMode)
        assertEquals(2, restored.entries.size)
        assertEquals(snapshot.entries[0].queueEntryId, restored.entries[0].queueEntryId)
        assertEquals(11L, restored.entries[0].musicId)
    }

    @Test
    fun clear_removesStoredSession() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences("playback_session", Context.MODE_PRIVATE).edit().clear().apply()
        val store = PlaybackSessionStore(context)
        store.clear()
        assertNull(store.load())
    }

    private fun testMusic(id: Long): MusicAbstract {
        return MusicAbstract(
            meta = MusicMeta(
                id = MusicId(id),
                title = "track-$id",
                duration = Duration.ofSeconds(1),
                order = emptyList(),
            ),
            cover = null,
        )
    }
}
