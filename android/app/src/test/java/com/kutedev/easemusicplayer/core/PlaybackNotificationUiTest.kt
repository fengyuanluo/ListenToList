package com.kutedev.easemusicplayer.core

import androidx.media3.session.CommandButton
import androidx.media3.common.MediaMetadata
import com.kutedev.easemusicplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ease_client_schema.PlayMode

class PlaybackNotificationUiTest {

    @Test
    fun buildPlaybackNotificationButtons_appendsPlayModeAndStopActions() {
        val buttons = buildPlaybackNotificationButtons(
            playMode = PlayMode.LIST_LOOP,
            playModeLabel = "Repeat list",
            stopLabel = "Stop playback",
        )

        assertEquals(2, buttons.size)

        val playModeButton = buttons[0]
        assertEquals(PLAYER_CYCLE_PLAY_MODE_COMMAND, playModeButton.sessionCommand?.customAction)
        assertEquals(R.drawable.icon_mode_repeat, playModeButton.iconResId)
        assertEquals("Repeat list", playModeButton.displayName)
        assertEquals(CommandButton.SLOT_OVERFLOW, playModeButton.slots[0])

        val stopButton = buttons[1]
        assertEquals(PLAYER_STOP_PLAYBACK_COMMAND, stopButton.sessionCommand?.customAction)
        assertEquals("Stop playback", stopButton.displayName)
        assertEquals(CommandButton.SLOT_OVERFLOW, stopButton.slots[0])
    }

    @Test
    fun resolvePlaybackNotificationContentText_prefersArtistThenAlbumThenFallback() {
        val withArtist = MediaMetadata.Builder()
            .setArtist("Artist")
            .setAlbumTitle("Album")
            .build()
        val withAlbumOnly = MediaMetadata.Builder()
            .setAlbumTitle("Album")
            .build()
        val empty = MediaMetadata.Builder().build()

        assertEquals("Artist", resolvePlaybackNotificationContentText(withArtist, "Ease Music Player"))
        assertEquals("Album", resolvePlaybackNotificationContentText(withAlbumOnly, "Ease Music Player"))
        assertEquals("Ease Music Player", resolvePlaybackNotificationContentText(empty, "Ease Music Player"))
    }

    @Test
    fun resolvePlaybackNotificationDisplayMetadata_prefersCurrentItemTitleOverStalePlayerTitle() {
        val itemMetadata = MediaMetadata.Builder()
            .setTitle("test-long.wav")
            .build()
        val playerMetadata = MediaMetadata.Builder()
            .setTitle("test-openlist-next.wav")
            .setArtist("Artist")
            .build()

        val displayMetadata = resolvePlaybackNotificationDisplayMetadata(
            itemMetadata = itemMetadata,
            playerMetadata = playerMetadata,
            fallbackTitle = "Ease Music Player",
            fallbackText = "Ease Music Player",
        )

        assertEquals("test-long.wav", displayMetadata.title)
        assertEquals("Artist", displayMetadata.text)
    }
}
