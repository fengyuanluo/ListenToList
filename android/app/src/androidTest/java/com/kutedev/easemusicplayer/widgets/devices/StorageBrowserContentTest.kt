package com.kutedev.easemusicplayer.widgets.devices

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import com.kutedev.easemusicplayer.viewmodels.BrowserScrollSnapshot
import com.kutedev.easemusicplayer.viewmodels.StorageSearchListUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.StorageId

@RunWith(AndroidJUnit4::class)
class StorageBrowserContentTest {
    @Test
    fun toggleSelectionScreenRendersOnDevice() {
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

        ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val selectedPaths = remember { mutableStateOf(setOf<String>()) }
                    val selectMode = remember { mutableStateOf(false) }
                    val selectedCount = entries.count { entry -> selectedPaths.value.contains(entry.path) }

                    StorageBrowserContent(
                        title = "Test Storage",
                        loadState = CurrentStorageStateType.OK,
                        searchSupported = true,
                        searchExpanded = false,
                        searchState = StorageSearchListUiState(),
                        currentPath = "/Music",
                        splitPaths = emptyList(),
                        entries = entries,
                        selectedPaths = selectedPaths.value,
                        selectedCount = selectedCount,
                        selectMode = selectMode.value,
                        disableToggleAll = false,
                        isRefreshing = false,
                        scrollSnapshot = BrowserScrollSnapshot(),
                        onBack = {},
                        onNavigateDir = {},
                        onExpandSearch = {},
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onCollapseSearch = {},
                        onSearchScopeChange = {},
                        onToggleAll = {},
                        onClickEntry = {},
                        onLongClickEntry = { entry ->
                            selectMode.value = true
                            selectedPaths.value = selectedPaths.value + entry.path
                        },
                        onClickSearchEntry = {},
                        onLongClickSearchEntry = {},
                        onLoadMoreSearch = {},
                        onRetrySearch = {},
                        onToggleEntry = { entry ->
                            val next = selectedPaths.value.toMutableSet()
                            if (!next.add(entry.path)) {
                                next.remove(entry.path)
                            }
                            selectedPaths.value = next
                        },
                        onDownloadSelected = {},
                        onAddSelectedToPlaylist = {},
                        onAddSelectedToQueue = {},
                        onRequestPermission = {},
                        onReload = {},
                        onScrollSnapshotChange = {}
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

    @Test
    fun refreshingScreenRendersOnDevice() {
        val storageId = StorageId(1)
        val entries = listOf(
            StorageEntry(
                storageId = storageId,
                name = "song.mp3",
                path = "/Music/song.mp3",
                size = 120uL,
                isDir = false
            )
        )

        ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    StorageBrowserContent(
                        title = "Test Storage",
                        loadState = CurrentStorageStateType.OK,
                        searchSupported = true,
                        searchExpanded = false,
                        searchState = StorageSearchListUiState(),
                        currentPath = "/Music",
                        splitPaths = emptyList(),
                        entries = entries,
                        selectedPaths = emptySet(),
                        selectedCount = 0,
                        selectMode = false,
                        disableToggleAll = false,
                        isRefreshing = true,
                        scrollSnapshot = BrowserScrollSnapshot(),
                        onBack = {},
                        onNavigateDir = {},
                        onExpandSearch = {},
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onCollapseSearch = {},
                        onSearchScopeChange = {},
                        onToggleAll = {},
                        onClickEntry = {},
                        onLongClickEntry = {},
                        onClickSearchEntry = {},
                        onLongClickSearchEntry = {},
                        onLoadMoreSearch = {},
                        onRetrySearch = {},
                        onToggleEntry = {},
                        onDownloadSelected = {},
                        onAddSelectedToPlaylist = {},
                        onAddSelectedToQueue = {},
                        onRequestPermission = {},
                        onReload = {},
                        onScrollSnapshotChange = {}
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
