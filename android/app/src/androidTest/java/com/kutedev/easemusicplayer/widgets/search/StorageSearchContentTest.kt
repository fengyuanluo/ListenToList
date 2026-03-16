package com.kutedev.easemusicplayer.widgets.search

import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutedev.easemusicplayer.debug.TestComposeActivity
import com.kutedev.easemusicplayer.viewmodels.StorageSearchSectionUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType

@RunWith(AndroidJUnit4::class)
class StorageSearchContentTest {
    @Test
    fun aggregateSearchScreenRendersWithResults() {
        val storage = Storage(
            id = StorageId(1),
            addr = "https://www.asmrgay.com",
            alias = "ASMRGAY",
            username = "",
            password = "",
            isAnonymous = true,
            typ = StorageType.OPEN_LIST,
            musicCount = 0uL,
        )
        val section = StorageSearchSectionUiState(
            storage = storage,
            entries = listOf(
                StorageSearchEntry(
                    storageId = storage.id,
                    name = "target-song.mp3",
                    path = "/music/target-song.mp3",
                    parentPath = "/music",
                    size = 12uL,
                    isDir = false,
                )
            ),
            total = 1,
            page = 1,
        )

        ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    StorageSearchContent(
                        query = "target",
                        scope = StorageSearchScope.ALL,
                        selectedStorageId = storage.id,
                        searchableStorages = listOf(storage),
                        sections = listOf(section),
                        onBack = {},
                        onQueryChange = {},
                        onScopeChange = {},
                        onClearQuery = {},
                        onSelectStorage = {},
                        onResultClick = {},
                        onResultLongClick = {},
                        onLoadMore = {},
                        onRetryStorage = {},
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
