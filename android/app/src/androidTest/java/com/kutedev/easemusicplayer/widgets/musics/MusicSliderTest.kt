package com.kutedev.easemusicplayer.widgets.musics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import com.kutedev.easemusicplayer.ui.theme.EaseMusicPlayerTheme
import com.kutedev.easemusicplayer.ui.theme.ThemeSettings
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicSliderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun tapSeekUsesAbsoluteTrackPosition() {
        var lastSeekTarget: ULong? = null

        setPlayerContent {
            MusicSlider(
                currentDurationMS = 10_000uL,
                bufferDurationMS = 15_000uL,
                totalDurationMS = 100_000uL,
                onChangeMusicPosition = { lastSeekTarget = it },
            )
        }

        val sliderBounds = composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .performTouchInput {
                val tapPoint = Offset(sliderBounds.width * 0.8f, sliderBounds.height / 2f)
                down(tapPoint)
                up()
            }

        composeRule.runOnIdle {
            val seekTarget = lastSeekTarget
            assertNotNull(seekTarget)
            assertTrue(abs(seekTarget!!.toLong() - 80_000L) <= 1_500L)
        }
    }

    @Test
    fun dragSeekStartsFromFingerPositionInsteadOfCurrentPlaybackPosition() {
        var lastSeekTarget: ULong? = null

        setPlayerContent {
            MusicSlider(
                currentDurationMS = 10_000uL,
                bufferDurationMS = 15_000uL,
                totalDurationMS = 100_000uL,
                onChangeMusicPosition = { lastSeekTarget = it },
            )
        }

        val sliderBounds = composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .performTouchInput {
                down(Offset(sliderBounds.width * 0.8f, sliderBounds.height / 2f))
                moveTo(Offset(sliderBounds.width * 0.9f, sliderBounds.height / 2f))
                up()
            }

        composeRule.runOnIdle {
            val seekTarget = lastSeekTarget
            assertNotNull(seekTarget)
            assertTrue(abs(seekTarget!!.toLong() - 90_000L) <= 4_000L)
        }
    }

    @Test
    fun delayedExternalSeekFeedbackKeepsOptimisticCurrentDurationVisibleAfterRelease() {
        setPlayerContent {
            var currentDurationMs by remember { mutableStateOf(5_000uL) }
            var pendingSeekTarget by remember { mutableStateOf(null as ULong?) }

            LaunchedEffect(pendingSeekTarget) {
                val target = pendingSeekTarget ?: return@LaunchedEffect
                delay(1_000)
                currentDurationMs = target
                pendingSeekTarget = null
            }

            MusicSlider(
                currentDurationMS = currentDurationMs,
                bufferDurationMS = 10_000uL,
                totalDurationMS = 100_000uL,
                onChangeMusicPosition = { pendingSeekTarget = it },
            )
        }

        val sliderBounds = composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        composeRule.onNodeWithTag(PLAYER_PROGRESS_SLIDER_TEST_TAG)
            .performTouchInput {
                down(Offset(sliderBounds.width * 0.3f, sliderBounds.height / 2f))
                moveTo(Offset(sliderBounds.width * 0.8f, sliderBounds.height / 2f))
                up()
            }

        composeRule.onNodeWithTag(PLAYER_PROGRESS_CURRENT_DURATION_TEST_TAG)
            .assertTextEquals("00:01:20")
    }

    private fun setPlayerContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            EaseMusicPlayerTheme(themeSettings = ThemeSettings()) {
                Box(modifier = Modifier.width(320.dp)) {
                    content()
                }
            }
        }
    }
}
