package com.kutedev.easemusicplayer.viewmodels

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.StorageId

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryBrowserControllerTest {
    @Test
    fun returningToFreshCachedParentSkipsReload() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        val rootEntry = dirEntry("/Music")
        val childEntry = musicEntry("/Music/song.mp3")
        service.enqueue("/") { ok(rootEntry) }
        service.enqueue("/Music") { ok(childEntry) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            controller.navigateTo("/Music")
            advanceUntilIdle()
            assertEquals(2, service.calls.size)

            controller.navigateUp()
            advanceUntilIdle()
            assertEquals(2, service.calls.size)
            assertEquals(listOf(rootEntry), controller.entries.value)
            assertEquals(CurrentStorageStateType.OK, controller.loadState.value)
            assertFalse(controller.isRefreshing.value)
        }
    }

    @Test
    fun staleCacheRevalidatesInBackgroundWithoutClearingList() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        val rootV1 = dirEntry("/Music")
        val rootV2 = dirEntry("/Podcasts")
        service.enqueue("/") { ok(rootV1) }
        service.enqueue("/") {
            delay(100)
            ok(rootV2)
        }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            clock.now += 31_000L
            controller.refresh(forceRemote = false)

            assertEquals(listOf(rootV1), controller.entries.value)
            assertEquals(CurrentStorageStateType.OK, controller.loadState.value)
            assertTrue(controller.isRefreshing.value)

            advanceUntilIdle()
            assertEquals(listOf(rootV2), controller.entries.value)
            assertFalse(controller.isRefreshing.value)
        }
    }

    @Test
    fun clearingStorageStateEvictsCacheAndForcesReload() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        val rootV1 = dirEntry("/Music")
        val rootV2 = dirEntry("/Podcasts")
        service.enqueue("/") { ok(rootV1) }
        service.enqueue("/") { ok(rootV2) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertEquals(listOf(rootV1), controller.entries.value)
            assertEquals(1, service.calls.size)

            controller.clearStorageState(REMOTE_STORAGE.storageId)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertEquals(listOf(rootV2), controller.entries.value)
            assertEquals(2, service.calls.size)
        }
    }

    @Test
    fun backgroundRefreshFailureKeepsVisibleEntries() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        val backgroundFailures = mutableListOf<CurrentStorageStateType>()
        val rootEntry = dirEntry("/Music")
        service.enqueue("/") { ok(rootEntry) }
        service.enqueue("/") {
            delay(100)
            ListStorageEntryChildrenResp.Timeout
        }

        createController(
            scope = this,
            clock = clock,
            service = service,
            onBackgroundRefreshFailed = { state -> backgroundFailures.add(state) },
        ).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            clock.now += 31_000L
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertEquals(listOf(rootEntry), controller.entries.value)
            assertEquals(CurrentStorageStateType.OK, controller.loadState.value)
            assertFalse(controller.isRefreshing.value)
            assertEquals(CurrentStorageStateType.TIMEOUT, controller.backgroundRefreshFailure.value)
            assertEquals(listOf(CurrentStorageStateType.TIMEOUT), backgroundFailures)

            service.enqueue("/Recovered") { ok(dirEntry("/Recovered/Albums")) }
            controller.navigateTo("/Recovered")
            advanceUntilIdle()

            assertEquals(null, controller.backgroundRefreshFailure.value)
        }
    }

    @Test
    fun latestNavigationWinsWhenOlderRequestReturnsLate() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        val aEntry = musicEntry("/A/song-a.mp3")
        val bEntry = musicEntry("/B/song-b.mp3")
        service.enqueue("/A") {
            delay(100)
            ok(aEntry)
        }
        service.enqueue("/B") { ok(bEntry) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)

            controller.navigateTo("/A")
            controller.navigateTo("/B")
            advanceUntilIdle()

            assertEquals("/B", controller.currentPath.value)
            assertEquals(listOf(bEntry), controller.entries.value)
            assertEquals(CurrentStorageStateType.OK, controller.loadState.value)
        }
    }

    @Test
    fun localStorageWithoutPermissionShowsPermissionErrorWithoutRemoteCall() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        createController(
            scope = this,
            clock = clock,
            service = service,
            hasLocalPermission = { false },
        ).use { harness ->
            val controller = harness.controller
            controller.setStorage(LOCAL_STORAGE)
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertEquals(CurrentStorageStateType.NEED_PERMISSION, controller.loadState.value)
            assertTrue(controller.entries.value.isEmpty())
            assertTrue(service.calls.isEmpty())
        }
    }

    @Test
    fun scrollSnapshotRestoresPerDirectory() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        service.enqueue("/") { ok(dirEntry("/Music")) }
        service.enqueue("/Music") { ok(musicEntry("/Music/song.mp3")) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            controller.setStorage(REMOTE_STORAGE)
            controller.restoreCurrentScrollSnapshot(BrowserScrollSnapshot(index = 3, offset = 12))
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            controller.updateScrollSnapshot(BrowserScrollSnapshot(index = 3, offset = 12))
            controller.navigateTo("/Music")
            advanceUntilIdle()
            assertEquals(BrowserScrollSnapshot(), controller.currentScrollSnapshot.value)

            controller.updateScrollSnapshot(BrowserScrollSnapshot(index = 5, offset = 42))
            controller.navigateUp()
            advanceUntilIdle()

            assertEquals(BrowserScrollSnapshot(index = 3, offset = 12), controller.currentScrollSnapshot.value)
        }
    }

    @Test
    fun navigationStopsAtDefaultDirectoryWhenCurrentPathStaysWithinSubtree() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        service.enqueue("/Music/Albums") { ok(dirEntry("/Music/Albums/Synthwave")) }
        service.enqueue("/Music/Albums/Synthwave") { ok(musicEntry("/Music/Albums/Synthwave/song.mp3")) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            backgroundScope.launch { controller.canNavigateUp.collect {} }
            controller.restorePath("/Music/Albums")
            controller.setStorage(
                REMOTE_STORAGE.copy(navigationRootCandidate = "/Music/Albums")
            )
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertFalse(controller.canNavigateUp.value)

            controller.navigateTo("/Music/Albums/Synthwave")
            advanceUntilIdle()
            assertTrue(controller.canNavigateUp.value)

            controller.navigateUp()
            advanceUntilIdle()

            assertEquals("/Music/Albums", controller.currentPath.value)
            assertFalse(controller.canNavigateUp.value)
        }
    }

    @Test
    fun navigationFallsBackToRootWhenCurrentPathIsOutsideDefaultDirectorySubtree() = runTest {
        val clock = FakeClock()
        val service = FakeDirectoryService()
        service.enqueue("/Podcasts/Episodes") { ok(musicEntry("/Podcasts/Episodes/episode.mp3")) }
        service.enqueue("/Podcasts") { ok(dirEntry("/Podcasts/Episodes")) }
        service.enqueue("/") { ok(dirEntry("/Podcasts")) }

        createController(this, clock, service).use { harness ->
            val controller = harness.controller
            backgroundScope.launch { controller.canNavigateUp.collect {} }
            controller.restorePath("/Podcasts/Episodes")
            controller.setStorage(
                REMOTE_STORAGE.copy(navigationRootCandidate = "/Music/Albums")
            )
            controller.refresh(forceRemote = false)
            advanceUntilIdle()

            assertTrue(controller.canNavigateUp.value)

            controller.navigateUp()
            advanceUntilIdle()
            assertEquals("/Podcasts", controller.currentPath.value)
            assertTrue(controller.canNavigateUp.value)

            controller.navigateUp()
            advanceUntilIdle()
            assertEquals("/", controller.currentPath.value)
            assertFalse(controller.canNavigateUp.value)
        }
    }

    @Test
    fun resolveBrowserNavigationFloorUsesDefaultDirectoryOnlyInsideItsSubtree() {
        assertEquals("/Music/Albums", resolveBrowserNavigationFloor("/Music/Albums", "/Music/Albums"))
        assertEquals("/Music/Albums", resolveBrowserNavigationFloor("/Music/Albums/Synthwave", "/Music/Albums"))
        assertEquals("/", resolveBrowserNavigationFloor("/Podcasts/Episodes", "/Music/Albums"))
    }

    private fun createController(
        scope: TestScope,
        clock: FakeClock,
        service: FakeDirectoryService,
        hasLocalPermission: () -> Boolean = { true },
        onBackgroundRefreshFailed: (CurrentStorageStateType) -> Unit = {},
    ): ControllerHarness {
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scope.testScheduler)
        )
        val controller = DirectoryBrowserController(
            scope = controllerScope,
            initialPath = "/",
            listEntriesRemote = { storageId, path -> service.list(storageId, path) },
            hasLocalPermission = hasLocalPermission,
            clock = clock,
            onBackgroundRefreshFailed = onBackgroundRefreshFailed,
        )
        return ControllerHarness(controller = controller, scope = controllerScope)
    }

    private fun ok(vararg entries: StorageEntry): ListStorageEntryChildrenResp {
        return ListStorageEntryChildrenResp.Ok(entries.toList())
    }

    private fun dirEntry(path: String): StorageEntry {
        return StorageEntry(
            storageId = REMOTE_STORAGE.storageId,
            name = path.substringAfterLast('/'),
            path = path,
            size = null,
            isDir = true,
        )
    }

    private fun musicEntry(path: String): StorageEntry {
        return StorageEntry(
            storageId = REMOTE_STORAGE.storageId,
            name = path.substringAfterLast('/'),
            path = path,
            size = 120uL,
            isDir = false,
        )
    }

    private class FakeClock(var now: Long = 0L) : BrowserClock {
        override fun nowMs(): Long {
            return now
        }
    }

    private class FakeDirectoryService {
        val calls = mutableListOf<Pair<StorageId, String>>()
        private val responses = mutableMapOf<String, ArrayDeque<suspend () -> ListStorageEntryChildrenResp>>()

        fun enqueue(path: String, response: suspend () -> ListStorageEntryChildrenResp) {
            val queue = responses.getOrPut(path) { ArrayDeque() }
            queue.addLast(response)
        }

        suspend fun list(storageId: StorageId, path: String): ListStorageEntryChildrenResp {
            calls += storageId to path
            val queue = responses[path] ?: error("missing fake response for path=$path")
            val response = if (queue.size > 1) {
                queue.removeFirst()
            } else {
                queue.first()
            }
            return response()
        }
    }

    private class ControllerHarness(
        val controller: DirectoryBrowserController,
        private val scope: CoroutineScope,
    ) : Closeable {
        override fun close() {
            scope.cancel()
        }
    }

    companion object {
        private val REMOTE_STORAGE = BrowserStorageContext(
            storageId = StorageId(1),
            isLocal = false,
        )
        private val LOCAL_STORAGE = BrowserStorageContext(
            storageId = StorageId(2),
            isLocal = true,
        )
    }
}
