package com.kutedev.easemusicplayer.viewmodels

import com.kutedev.easemusicplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ease_client_schema.PlayMode

class PlayerPlayModeLabelTest {

    @Test
    fun playModeToastLabelResIdMapsEveryModeToTheExpectedString() {
        assertEquals(R.string.music_play_mode_single, playModeToastLabelResId(PlayMode.SINGLE))
        assertEquals(
            R.string.music_play_mode_single_loop,
            playModeToastLabelResId(PlayMode.SINGLE_LOOP),
        )
        assertEquals(R.string.music_play_mode_list, playModeToastLabelResId(PlayMode.LIST))
        assertEquals(R.string.music_play_mode_list_loop, playModeToastLabelResId(PlayMode.LIST_LOOP))
    }
}
