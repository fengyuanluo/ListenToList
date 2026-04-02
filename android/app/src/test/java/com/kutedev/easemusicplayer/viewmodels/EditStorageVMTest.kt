package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.SavedStateHandle
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.ease_client_schema.StorageType

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EditStorageVMTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoresExpandedDefaultPathBrowserStateForWebdav() {
        val savedStateHandle = savedStateHandleFor(
            type = StorageType.WEBDAV,
            password = "secret",
            currentPath = "/Music/Sub",
        )

        createViewModel(savedStateHandle).use { vm ->
            assertTrue(vm.viewModel.defaultPathBrowserExpanded.value)
            assertEquals("/Music/Sub", vm.viewModel.defaultPathBrowserCurrentPath.value)
        }
    }

    @Test
    fun restoresExpandedDefaultPathBrowserStateForOneDrive() {
        val savedStateHandle = savedStateHandleFor(
            type = StorageType.ONE_DRIVE,
            addr = "",
            username = "",
            isAnonymous = false,
            password = "refresh-token",
            currentPath = "/Documents/Albums",
        )

        createViewModel(savedStateHandle).use { vm ->
            assertTrue(vm.viewModel.defaultPathBrowserExpanded.value)
            assertEquals("/Documents/Albums", vm.viewModel.defaultPathBrowserCurrentPath.value)
        }
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle): ViewModelHarness {
        val scope = CoroutineScope(Job() + dispatcher)
        val toastRepository = ToastRepository(scope)
        val bridge = Bridge(RuntimeEnvironment.getApplication().applicationContext, toastRepository)
        val storageRepository = StorageRepository(bridge, scope)
        val viewModel = EditStorageVM(
            bridge = bridge,
            storageRepository = storageRepository,
            toastRepository = toastRepository,
            savedStateHandle = savedStateHandle,
        )
        return ViewModelHarness(viewModel, scope)
    }

    private fun savedStateHandleFor(
        type: StorageType,
        addr: String = "https://example.com",
        username: String = "user",
        isAnonymous: Boolean = false,
        password: String,
        currentPath: String,
    ): SavedStateHandle {
        return SavedStateHandle(
            mapOf(
                "id" to 7L,
                "edit_storage_form_type" to type.name,
                "edit_storage_form_addr" to addr,
                "edit_storage_form_alias" to "Demo",
                "edit_storage_form_username" to username,
                "edit_storage_form_password" to password,
                "edit_storage_form_is_anonymous" to isAnonymous,
                "edit_storage_form_default_path" to "/Music",
                "edit_storage_form_storage_value" to 7L,
                "edit_storage_default_path_browser_expanded" to true,
                "edit_storage_default_path_browser_current_path" to currentPath,
                "edit_storage_default_path_browser_scroll_index" to 3,
                "edit_storage_default_path_browser_scroll_offset" to 18,
            )
        )
    }

    private class ViewModelHarness(
        val viewModel: EditStorageVM,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
        }
    }
}
