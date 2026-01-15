package com.kutedev.easemusicplayer.widgets.devices

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.StorageId

@RunWith(AndroidJUnit4::class)
class StorageBrowserContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toggleSelectionShowsImportButton() {
        val storageId = StorageId(1)
        val entries = listOf(
            StorageEntry(
                storageId = storageId,
                name = "Music",
                path = "/Music",
                size = null,
                isDir = true
            ),
            StorageEntry(
                storageId = storageId,
                name = "song.mp3",
                path = "/Music/song.mp3",
                size = 120uL,
                isDir = false
            )
        )

        composeRule.setContent {
            val selectedPaths = remember { mutableStateOf(setOf<String>()) }
            val selectMode = remember { mutableStateOf(false) }
            val selectedCount = entries.count { entry -> selectedPaths.value.contains(entry.path) }

            StorageBrowserContent(
                title = "Test Storage",
                loadState = CurrentStorageStateType.OK,
                splitPaths = emptyList(),
                entries = entries,
                selectedPaths = selectedPaths.value,
                selectedCount = selectedCount,
                selectMode = selectMode.value,
                disableToggleAll = false,
                onBack = {},
                onNavigateDir = {},
                onToggleAll = {},
                onToggleSelectMode = { selectMode.value = !selectMode.value },
                onClickEntry = {},
                onToggleEntry = { entry ->
                    val next = selectedPaths.value.toMutableSet()
                    if (!next.add(entry.path)) {
                        next.remove(entry.path)
                    }
                    selectedPaths.value = next
                },
                onImportSelected = {},
                onRequestPermission = {},
                onReload = {}
            )
        }

        assertEquals(
            0,
            composeRule.onAllNodesWithTag("storage_browser_fab").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("storage_browser_checkbox_1").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag("storage_browser_toggle_select_mode").performClick()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("storage_browser_checkbox_1").fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag("storage_browser_checkbox_1").performClick()
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("storage_browser_fab").fetchSemanticsNodes().size
        )
    }
}
