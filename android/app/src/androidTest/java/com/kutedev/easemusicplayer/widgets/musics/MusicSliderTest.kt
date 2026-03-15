package com.kutedev.easemusicplayer.widgets.musics

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicSliderTest {
    @Test
    fun displayTotalDurationText() {
        ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MusicSlider(
                        currentDuration = "00:00:05",
                        _currentDurationMS = 5uL * 1000uL,
                        bufferDurationMS = 10uL * 1000uL,
                        totalDuration = "00:03:00",
                        totalDurationMS = 180uL * 1000uL,
                        onChangeMusicPosition = {}
                    )
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue(root.childCount > 0)
            }
        }
    }
}
