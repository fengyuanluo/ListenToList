package com.kutedev.easemusicplayer.widgets.playlists

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistHomeSearchEntryTest {
    @Test
    fun homeSearchEntryRendersOnDevice() {
        ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val queryState = remember { mutableStateOf("") }
                    PlaylistHomeSearchEntry(
                        query = queryState.value,
                        onQueryChange = { value -> queryState.value = value },
                        onSearch = {},
                        onClearQuery = { queryState.value = "" },
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
