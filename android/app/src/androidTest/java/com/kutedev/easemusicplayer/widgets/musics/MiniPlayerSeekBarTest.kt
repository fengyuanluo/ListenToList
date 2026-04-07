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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
class MiniPlayerSeekBarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun tapSeekKeepsMiniPlayerAtOptimisticValueUntilFeedbackArrives() {
        var lastSeekTarget: ULong? = null

        setMiniPlayerContent {
            var currentDurationMs by remember { mutableStateOf(5_000uL) }
            var pendingSeekTarget by remember { mutableStateOf(null as ULong?) }

            LaunchedEffect(pendingSeekTarget) {
                val target = pendingSeekTarget ?: return@LaunchedEffect
                delay(1_000)
                currentDurationMs = target
                pendingSeekTarget = null
            }

            MiniPlayerSeekBar(
                currentDurationMS = currentDurationMs,
                totalDurationMS = 100_000uL,
                onSeek = {
                    lastSeekTarget = it
                    pendingSeekTarget = it
                },
            )
        }

        composeRule.onNodeWithTag(MINI_PLAYER_SEEK_BAR_TEST_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(80_000f)
            }

        composeRule.runOnIdle {
            val seekTarget = lastSeekTarget
            assertNotNull(seekTarget)
            assertTrue(abs(seekTarget!!.toLong() - 80_000L) <= 1_500L)
        }

        composeRule.onNodeWithTag(MINI_PLAYER_SEEK_BAR_TEST_TAG)
            .assert(progressValueNear(80_000f, tolerance = 1_500f))
    }

    private fun progressValueNear(
        expected: Float,
        tolerance: Float,
    ): SemanticsMatcher {
        return SemanticsMatcher("has progress near $expected") { node ->
            if (!node.config.contains(SemanticsProperties.ProgressBarRangeInfo)) {
                return@SemanticsMatcher false
            }
            val rangeInfo = node.config[SemanticsProperties.ProgressBarRangeInfo]
            hasProgressNear(rangeInfo, expected, tolerance)
        }
    }

    private fun hasProgressNear(
        rangeInfo: ProgressBarRangeInfo,
        expected: Float,
        tolerance: Float,
    ): Boolean {
        return abs(rangeInfo.current - expected) <= tolerance
    }

    private fun setMiniPlayerContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            EaseMusicPlayerTheme(themeSettings = ThemeSettings()) {
                Box(modifier = Modifier.width(320.dp)) {
                    content()
                }
            }
        }
    }
}
