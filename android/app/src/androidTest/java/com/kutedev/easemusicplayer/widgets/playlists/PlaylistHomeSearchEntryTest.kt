package com.kutedev.easemusicplayer.widgets.playlists

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistHomeSearchEntryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun homeSearchEntryRendersAndAcceptsInput() {
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            PlaylistHomeSearchEntry(
                query = query,
                onQueryChange = { value -> query = value },
                onSearch = {},
                onClearQuery = { query = "" },
            )
        }

        composeRule.onNodeWithTag("playlist_home_search_entry").performTextInput("asmr")
        composeRule.waitForIdle()
    }
}
