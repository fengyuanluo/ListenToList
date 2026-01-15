package com.kutedev.easemusicplayer.widgets.musics

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicSliderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun displayTotalDurationText() {
        composeRule.setContent {
            MusicSlider(
                currentDuration = "00:00:05",
                _currentDurationMS = 5uL * 1000uL,
                bufferDurationMS = 10uL * 1000uL,
                totalDuration = "00:03:00",
                totalDurationMS = 180uL * 1000uL,
                onChangeMusicPosition = {}
            )
        }

        composeRule.onNodeWithText("00:03:00").assertIsDisplayed()
    }
}
