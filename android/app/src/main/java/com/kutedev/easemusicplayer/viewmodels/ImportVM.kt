package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.ImportRepository
import com.kutedev.easemusicplayer.singleton.PermissionRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ctListStorageEntryChildren
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType

private const val STATE_CURRENT_PATH = "import_browser_current_path"
private const val STATE_SELECTED_STORAGE_ID = "import_browser_selected_storage_id"
private const val STATE_SELECTED_PATHS = "import_browser_selected_paths"
private const val STATE_SCROLL_INDEX = "import_browser_scroll_index"
private const val STATE_SCROLL_OFFSET = "import_browser_scroll_offset"

@HiltViewModel
class ImportVM @Inject constructor(
    private val storageRepository: StorageRepository,
    private val importRepository: ImportRepository,
    private val permissionRepository: PermissionRepository,
    private val toastRepository: ToastRepository,
    private val bridge: Bridge,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val restoredPath = savedStateHandle[STATE_CURRENT_PATH] ?: "/"
    private val restoredScrollSnapshot = BrowserScrollSnapshot(
        index = savedStateHandle[STATE_SCROLL_INDEX] ?: 0,
        offset = savedStateHandle[STATE_SCROLL_OFFSET] ?: 0,
    )
    private val restoredSelectedPaths = (savedStateHandle.get<ArrayList<String>>(STATE_SELECTED_PATHS)
        ?: arrayListOf())
    private val restoredStorageIdValue: Long? = savedStateHandle[STATE_SELECTED_STORAGE_ID]
    private val _selected = MutableStateFlow(
        restoredSelectedPaths.fold(persistentHashSetOf<String>()) { acc, path ->
            acc.add(path)
        }
    )
    private val _selectedStorageId = MutableStateFlow(
        restoredStorageIdValue?.let(::StorageId) ?: storageRepository.storages.value.firstOrNull()?.id
    )
    private val _hasAvailableStorage = MutableStateFlow(storageRepository.storages.value.isNotEmpty())

    private val browser = DirectoryBrowserController(
        scope = viewModelScope,
        initialPath = restoredPath,
        initialScrollSnapshot = restoredScrollSnapshot,
        listEntriesRemote = { targetStorageId, path ->
            bridge.runRaw {
                ctListStorageEntryChildren(
                    it,
                    StorageEntryLoc(
                        storageId = targetStorageId,
                        path = path,
                    )
                )
            }
        },
        hasLocalPermission = { permissionRepository.havePermission.value },
        onPersistPath = { savedStateHandle[STATE_CURRENT_PATH] = it },
        onPersistScrollSnapshot = { snapshot ->
            savedStateHandle[STATE_SCROLL_INDEX] = snapshot.index
            savedStateHandle[STATE_SCROLL_OFFSET] = snapshot.offset
        },
        onBackgroundRefreshFailed = { state ->
            emitLoadFailureToast(state)
        }
    )

    val splitPaths = browser.splitPaths
    val currentPath = browser.currentPath
    val currentScrollSnapshot = browser.currentScrollSnapshot
    val entries = browser.entries
    val selected = _selected.asStateFlow()
    val allowTypes = importRepository.allowTypes
    val selectedStorageId = _selectedStorageId.asStateFlow()
    val loadState = browser.loadState
    val isRefreshing = browser.isRefreshing
    val canNavigateUp = browser.canNavigateUp
    val hasAvailableStorage = _hasAvailableStorage.asStateFlow()
    val selectedCount = _selected.combine(entries) { selected, currentEntries ->
        currentEntries.count { entry -> selected.contains(entry.path) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val disabledToggleAll = combine(entries, allowTypes) { currentEntries, allowedTypes ->
        currentEntries.none { entry -> allowedTypes.contains(entry.entryTyp()) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, true)

    init {
        viewModelScope.launch {
            var hasBoundStorage = false
            storageRepository.storages.collectLatest { storages ->
                _hasAvailableStorage.value = storages.isNotEmpty()
                val selectedId = _selectedStorageId.value
                val selectedStorage = storages.find { it.id == selectedId }
                when {
                    selectedStorage != null -> {
                        browser.setStorage(selectedStorage.toBrowserContext())
                        if (!hasBoundStorage) {
                            browser.restorePath(restoredPath)
                            browser.restoreCurrentScrollSnapshot(restoredScrollSnapshot)
                            browser.refresh(forceRemote = false)
                            hasBoundStorage = true
                        } else {
                            browser.refresh(forceRemote = true)
                        }
                    }

                    storages.isNotEmpty() -> {
                        val fallback = storages.first()
                        val hadMissingSelection = selectedId != null
                        applySelectedStorage(
                            storage = fallback,
                            path = "/",
                            resetSelection = true,
                            forceRemote = false,
                        )
                        hasBoundStorage = true
                        if (hadMissingSelection) {
                            toastRepository.emitToast("当前设备已不存在，已切换到其他设备")
                        }
                    }

                    else -> {
                        _selectedStorageId.value = null
                        persistSelectedStorageId()
                        clearSelection()
                        browser.setStorage(null)
                        browser.restorePath("/")
                        browser.clearVisibleState()
                        hasBoundStorage = false
                    }
                }
            }
        }
        viewModelScope.launch {
            permissionRepository.havePermission.drop(1).collectLatest {
                browser.refresh(forceRemote = true)
            }
        }
        viewModelScope.launch {
            entries.collectLatest { currentEntries ->
                trimSelectionToEntries(currentEntries)
            }
        }
    }

    fun clickEntry(entry: StorageEntry) {
        if (entry.isDir) {
            navigateDir(entry.path)
        } else if (allowTypes.value.contains(entry.entryTyp())) {
            toggleSelect(entry.path)
        }
    }

    fun navigateDir(path: String) {
        val normalized = normalizeBrowserPath(path)
        if (browser.currentPathValue() == normalized) {
            return
        }
        browser.navigateTo(normalized)
    }

    fun navigateUp() {
        browser.navigateUp()
    }

    private fun toggleSelect(path: String) {
        val selected = _selected.value
        val next = if (selected.contains(path)) {
            selected.remove(path)
        } else {
            selected.add(path)
        }
        _selected.value = next
        persistSelectedPaths()
    }

    fun finish() {
        val currentSelected = entries.value.filter { entry -> _selected.value.contains(entry.path) }
        importRepository.onFinish(currentSelected)
    }

    fun requestPermission() {
        permissionRepository.requestStoragePermission()
    }

    fun selectStorage(storageId: StorageId) {
        val storage = storageRepository.storages.value.find { it.id == storageId } ?: return
        applySelectedStorage(
            storage = storage,
            path = "/",
            resetSelection = true,
            forceRemote = false,
        )
    }

    fun toggleAll() {
        val selectable = entries.value.filter { entry ->
            allowTypes.value.contains(entry.entryTyp())
        }
        val allSelected = selectable.isNotEmpty() && selectable.all { _selected.value.contains(it.path) }
        _selected.value = if (allSelected) {
            _selected.value.clear()
        } else {
            selectable.fold(persistentHashSetOf<String>()) { acc, entry ->
                acc.add(entry.path)
            }
        }
        persistSelectedPaths()
    }

    fun reload() {
        browser.refresh(forceRemote = true)
    }

    fun updateCurrentScrollSnapshot(index: Int, offset: Int) {
        browser.updateScrollSnapshot(BrowserScrollSnapshot(index = index, offset = offset))
    }

    private fun applySelectedStorage(
        storage: Storage,
        path: String,
        resetSelection: Boolean,
        forceRemote: Boolean,
    ) {
        _selectedStorageId.value = storage.id
        persistSelectedStorageId()
        if (resetSelection) {
            clearSelection()
        }
        browser.setStorage(storage.toBrowserContext())
        browser.restorePath(path)
        browser.restoreCurrentScrollSnapshot(BrowserScrollSnapshot())
        browser.refresh(forceRemote = forceRemote)
    }

    private fun clearSelection() {
        _selected.value = _selected.value.clear()
        persistSelectedPaths()
    }

    private fun trimSelectionToEntries(currentEntries: List<StorageEntry>) {
        val selectablePaths = currentEntries
            .filter { allowTypes.value.contains(it.entryTyp()) }
            .map { it.path }
            .toSet()
        val trimmed = retainPaths(_selected.value, selectablePaths)
        if (trimmed != _selected.value) {
            _selected.value = trimmed
            persistSelectedPaths()
        }
    }

    private fun retainPaths(
        selected: PersistentSet<String>,
        allowedPaths: Set<String>,
    ): PersistentSet<String> {
        var next = persistentHashSetOf<String>()
        for (path in selected) {
            if (allowedPaths.contains(path)) {
                next = next.add(path)
            }
        }
        return next
    }

    private fun persistSelectedStorageId() {
        savedStateHandle[STATE_SELECTED_STORAGE_ID] = _selectedStorageId.value?.value
    }

    private fun persistSelectedPaths() {
        savedStateHandle[STATE_SELECTED_PATHS] = ArrayList(_selected.value)
    }

    private fun emitLoadFailureToast(state: CurrentStorageStateType) {
        when (state) {
            CurrentStorageStateType.AUTHENTICATION_FAILED -> {
                toastRepository.emitToast("认证失败，请检查设备配置")
            }

            CurrentStorageStateType.TIMEOUT -> {
                toastRepository.emitToast("连接超时，请重试")
            }

            CurrentStorageStateType.UNKNOWN_ERROR -> {
                toastRepository.emitToast("加载失败，请重试")
            }

            CurrentStorageStateType.NEED_PERMISSION -> {
                toastRepository.emitToast("需要授权后才能继续访问本地设备")
            }

            else -> Unit
        }
    }

    private fun Storage.toBrowserContext(): BrowserStorageContext {
        return BrowserStorageContext(
            storageId = id,
            isLocal = typ == StorageType.LOCAL,
        )
    }
}

class VImportStorageEntry(private val storage: Storage) {
    val id: StorageId
        get() = storage.id

    val isLocal: Boolean
        get() = storage.typ == StorageType.LOCAL

    val name: String
        get() {
            if (storage.alias != "") {
                return storage.alias
            }
            return storage.addr
        }

    val subtitle: String
        get() {
            if (storage.alias != "") {
                return storage.addr
            }
            return ""
        }
}
