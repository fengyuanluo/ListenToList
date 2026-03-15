package com.kutedev.easemusicplayer.viewmodels

import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.StorageId

private const val DIRECTORY_CACHE_TTL_MS = 30_000L
private const val DIRECTORY_CACHE_LIMIT_PER_STORAGE = 32

data class BrowserPathItem(
    val path: String,
    val name: String,
)

data class BrowserScrollSnapshot(
    val index: Int = 0,
    val offset: Int = 0,
)

data class DirectoryCacheKey(
    val storageId: StorageId,
    val path: String,
)

data class DirectoryCacheEntry(
    val entries: List<StorageEntry>,
    val updatedAtMs: Long,
) {
    fun isFresh(nowMs: Long): Boolean {
        return nowMs - updatedAtMs <= DIRECTORY_CACHE_TTL_MS
    }
}

data class BrowserStorageContext(
    val storageId: StorageId,
    val isLocal: Boolean,
)

data class DirectoryFetchSuccess(
    val entries: List<StorageEntry>,
    val fromCache: Boolean,
)

data class DirectoryFetchFailure(
    val state: CurrentStorageStateType,
)

sealed interface DirectoryFetchResult {
    data class Success(val value: DirectoryFetchSuccess) : DirectoryFetchResult
    data class Failure(val value: DirectoryFetchFailure) : DirectoryFetchResult
}

fun normalizeBrowserPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank() || trimmed == "/") {
        return "/"
    }
    val withLeadingSlash = if (trimmed.startsWith("/")) {
        trimmed
    } else {
        "/$trimmed"
    }
    return withLeadingSlash.trimEnd('/').ifBlank { "/" }
}

fun parentBrowserPath(path: String): String {
    val normalized = normalizeBrowserPath(path)
    if (normalized == "/") {
        return "/"
    }
    val trimmed = normalized.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

fun buildBrowserPathItems(path: String): List<BrowserPathItem> {
    val normalized = normalizeBrowserPath(path)
    val components = normalized.split('/').filter { it.isNotEmpty() }
    val splitPaths = mutableListOf<BrowserPathItem>()

    var currentPath = ""
    for (component in components) {
        currentPath = if (currentPath.isEmpty()) {
            "/$component"
        } else {
            "$currentPath/$component"
        }
        val name = try {
            URLDecoder.decode(component, "UTF-8")
        } catch (_: Exception) {
            component
        }
        splitPaths.add(BrowserPathItem(currentPath, name))
    }
    return splitPaths
}

fun interface BrowserClock {
    fun nowMs(): Long
}

object SystemBrowserClock : BrowserClock {
    override fun nowMs(): Long {
        return System.currentTimeMillis()
    }
}

class DirectoryBrowserController(
    private val scope: CoroutineScope,
    initialPath: String,
    initialScrollSnapshot: BrowserScrollSnapshot = BrowserScrollSnapshot(),
    private val listEntriesRemote: suspend (storageId: StorageId, path: String) -> ListStorageEntryChildrenResp,
    private val hasLocalPermission: () -> Boolean,
    private val clock: BrowserClock = SystemBrowserClock,
    private val onPersistPath: (String) -> Unit = {},
    private val onPersistScrollSnapshot: (BrowserScrollSnapshot) -> Unit = {},
    private val onBackgroundRefreshFailed: (CurrentStorageStateType) -> Unit = {},
) {
    private val cache = LinkedHashMap<DirectoryCacheKey, DirectoryCacheEntry>(16, 0.75f, true)
    private val scrollSnapshots = mutableMapOf<DirectoryCacheKey, BrowserScrollSnapshot>()
    private val _storageContext = MutableStateFlow<BrowserStorageContext?>(null)
    private val _currentPath = MutableStateFlow(normalizeBrowserPath(initialPath))
    private val _currentScrollSnapshot = MutableStateFlow(initialScrollSnapshot)
    private val _entries = MutableStateFlow(emptyList<StorageEntry>())
    private val _loadState = MutableStateFlow(CurrentStorageStateType.LOADING)
    private val _isRefreshing = MutableStateFlow(false)

    private var loadJob: Job? = null
    private var requestSeq: Long = 0

    val currentPath = _currentPath.asStateFlow()
    val currentScrollSnapshot = _currentScrollSnapshot.asStateFlow()
    val entries = _entries.asStateFlow()
    val loadState = _loadState.asStateFlow()
    val isRefreshing = _isRefreshing.asStateFlow()
    val splitPaths: StateFlow<List<BrowserPathItem>> = _currentPath.map(::buildBrowserPathItems)
        .stateIn(scope, SharingStarted.Lazily, buildBrowserPathItems(initialPath))
    val canNavigateUp: StateFlow<Boolean> = _currentPath.map { it != "/" }
        .stateIn(scope, SharingStarted.Lazily, normalizeBrowserPath(initialPath) != "/")

    fun setStorage(context: BrowserStorageContext?) {
        _storageContext.value = context
        syncCurrentScrollSnapshot()
        if (context == null) {
            clearVisibleState()
        }
    }

    fun navigateTo(path: String) {
        val normalized = normalizeBrowserPath(path)
        if (_currentPath.value == normalized) {
            return
        }
        _currentPath.value = normalized
        onPersistPath(normalized)
        syncCurrentScrollSnapshot()
        loadCurrent(forceRemote = false)
    }

    fun navigateUp() {
        if (_currentPath.value == "/") {
            return
        }
        navigateTo(parentBrowserPath(_currentPath.value))
    }

    fun restorePath(path: String) {
        val normalized = normalizeBrowserPath(path)
        if (_currentPath.value != normalized) {
            _currentPath.value = normalized
        }
        onPersistPath(normalized)
        syncCurrentScrollSnapshot()
    }

    fun restoreCurrentScrollSnapshot(snapshot: BrowserScrollSnapshot) {
        val key = currentCacheKey() ?: return
        scrollSnapshots[key] = snapshot
        _currentScrollSnapshot.value = snapshot
        onPersistScrollSnapshot(snapshot)
    }

    fun updateScrollSnapshot(snapshot: BrowserScrollSnapshot) {
        val key = currentCacheKey() ?: return
        scrollSnapshots[key] = snapshot
        _currentScrollSnapshot.value = snapshot
        onPersistScrollSnapshot(snapshot)
    }

    fun refresh(forceRemote: Boolean = true) {
        loadCurrent(forceRemote = forceRemote)
    }

    suspend fun readDirectory(path: String, forceRemote: Boolean = false): DirectoryFetchResult {
        val storage = _storageContext.value
            ?: return DirectoryFetchResult.Failure(
                DirectoryFetchFailure(CurrentStorageStateType.UNKNOWN_ERROR)
            )
        val normalized = normalizeBrowserPath(path)
        if (storage.isLocal && !hasLocalPermission()) {
            return DirectoryFetchResult.Failure(
                DirectoryFetchFailure(CurrentStorageStateType.NEED_PERMISSION)
            )
        }
        val key = DirectoryCacheKey(storage.storageId, normalized)
        val cached = cache[key]
        if (!forceRemote && cached != null && cached.isFresh(clock.nowMs())) {
            return DirectoryFetchResult.Success(
                DirectoryFetchSuccess(entries = cached.entries, fromCache = true)
            )
        }
        return fetchRemote(storage, normalized, key)
    }

    fun clearVisibleState() {
        cancelActiveLoad()
        _entries.value = emptyList()
        _loadState.value = CurrentStorageStateType.OK
        _isRefreshing.value = false
    }

    fun currentPathValue(): String {
        return _currentPath.value
    }

    private fun loadCurrent(forceRemote: Boolean) {
        val storage = _storageContext.value ?: run {
            clearVisibleState()
            return
        }
        if (storage.isLocal && !hasLocalPermission()) {
            cancelActiveLoad()
            _entries.value = emptyList()
            _loadState.value = CurrentStorageStateType.NEED_PERMISSION
            _isRefreshing.value = false
            return
        }

        val path = _currentPath.value
        val key = DirectoryCacheKey(storage.storageId, path)
        val cached = cache[key]
        val shouldUseCachedWithoutReload = !forceRemote && cached != null && cached.isFresh(clock.nowMs())
        if (shouldUseCachedWithoutReload) {
            applyCacheEntry(cached)
            _isRefreshing.value = false
            _loadState.value = CurrentStorageStateType.OK
            return
        }

        val hasCachedEntry = cached != null
        if (hasCachedEntry) {
            applyCacheEntry(cached)
            _loadState.value = CurrentStorageStateType.OK
            _isRefreshing.value = true
        } else {
            _entries.value = emptyList()
            _loadState.value = CurrentStorageStateType.LOADING
            _isRefreshing.value = false
        }

        startLoad(
            storage = storage,
            path = path,
            key = key,
            keepVisibleStateOnFailure = hasCachedEntry,
        )
    }

    private fun startLoad(
        storage: BrowserStorageContext,
        path: String,
        key: DirectoryCacheKey,
        keepVisibleStateOnFailure: Boolean,
    ) {
        cancelActiveLoad()
        val currentRequestSeq = ++requestSeq
        loadJob = scope.launch {
            val result = fetchRemote(storage, path, key)
            if (!isActive) {
                return@launch
            }
            val currentStorage = _storageContext.value
            val isStillCurrent = currentRequestSeq == requestSeq &&
                currentStorage?.storageId == storage.storageId &&
                _currentPath.value == path
            if (!isStillCurrent) {
                return@launch
            }
            when (result) {
                is DirectoryFetchResult.Success -> {
                    _entries.value = result.value.entries
                    _loadState.value = CurrentStorageStateType.OK
                    _isRefreshing.value = false
                }

                is DirectoryFetchResult.Failure -> {
                    _isRefreshing.value = false
                    if (keepVisibleStateOnFailure) {
                        _loadState.value = CurrentStorageStateType.OK
                        onBackgroundRefreshFailed(result.value.state)
                    } else {
                        _entries.value = emptyList()
                        _loadState.value = result.value.state
                    }
                }
            }
        }
    }

    private fun cancelActiveLoad() {
        loadJob?.cancel()
        loadJob = null
        requestSeq += 1
    }

    private suspend fun fetchRemote(
        storage: BrowserStorageContext,
        path: String,
        key: DirectoryCacheKey,
    ): DirectoryFetchResult {
        val resp = listEntriesRemote(storage.storageId, path)
        return when (resp) {
            is ListStorageEntryChildrenResp.Ok -> {
                val entries = resp.v1
                putCache(key, entries)
                DirectoryFetchResult.Success(
                    DirectoryFetchSuccess(entries = entries, fromCache = false)
                )
            }

            ListStorageEntryChildrenResp.AuthenticationFailed -> {
                DirectoryFetchResult.Failure(
                    DirectoryFetchFailure(CurrentStorageStateType.AUTHENTICATION_FAILED)
                )
            }

            ListStorageEntryChildrenResp.Timeout -> {
                DirectoryFetchResult.Failure(
                    DirectoryFetchFailure(CurrentStorageStateType.TIMEOUT)
                )
            }

            ListStorageEntryChildrenResp.Unknown -> {
                DirectoryFetchResult.Failure(
                    DirectoryFetchFailure(CurrentStorageStateType.UNKNOWN_ERROR)
                )
            }
        }
    }

    private fun putCache(key: DirectoryCacheKey, entries: List<StorageEntry>) {
        cache[key] = DirectoryCacheEntry(entries = entries, updatedAtMs = clock.nowMs())
        trimCacheForStorage(key.storageId)
    }

    private fun trimCacheForStorage(storageId: StorageId) {
        while (cache.keys.count { it.storageId == storageId } > DIRECTORY_CACHE_LIMIT_PER_STORAGE) {
            val eldestKey = cache.keys.firstOrNull { it.storageId == storageId } ?: break
            cache.remove(eldestKey)
            scrollSnapshots.remove(eldestKey)
        }
    }

    private fun applyCacheEntry(entry: DirectoryCacheEntry) {
        _entries.value = entry.entries
    }

    private fun syncCurrentScrollSnapshot() {
        val key = currentCacheKey() ?: run {
            _currentScrollSnapshot.value = BrowserScrollSnapshot()
            return
        }
        _currentScrollSnapshot.value = scrollSnapshots[key] ?: BrowserScrollSnapshot()
    }

    private fun currentCacheKey(): DirectoryCacheKey? {
        val storage = _storageContext.value ?: return null
        return DirectoryCacheKey(storage.storageId, _currentPath.value)
    }
}
