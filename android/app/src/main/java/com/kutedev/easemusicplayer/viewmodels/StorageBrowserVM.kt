package com.kutedev.easemusicplayer.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutedev.easemusicplayer.core.FolderPrefetcher
import com.kutedev.easemusicplayer.core.PLAYBACK_SOURCE_TAG_FOLDER_PREFETCH
import com.kutedev.easemusicplayer.core.PlaybackCache
import com.kutedev.easemusicplayer.core.PlaybackCachePolicy
import com.kutedev.easemusicplayer.core.PlaybackDataSourceFactory
import com.kutedev.easemusicplayer.core.buildPlaybackMusicUri
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.PermissionRepository
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.min
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.ArgCreatePlaylist
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ToAddMusicEntry
import uniffi.ease_client_backend.ctCreatePlaylist
import uniffi.ease_client_backend.ctListStorageEntryChildren
import uniffi.ease_client_backend.ctRemovePlaylist
import uniffi.ease_client_schema.StorageEntryLoc
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType

private const val MAX_FOLDER_PREFETCH = 12
private const val STATE_CURRENT_PATH = "storage_browser_current_path"
private const val STATE_SELECTED_PATHS = "storage_browser_selected_paths"
private const val STATE_SELECT_MODE = "storage_browser_select_mode"
private const val STATE_SCROLL_INDEX = "storage_browser_scroll_index"
private const val STATE_SCROLL_OFFSET = "storage_browser_scroll_offset"

@HiltViewModel
class StorageBrowserVM @Inject constructor(
    private val storageRepository: StorageRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    private val permissionRepository: PermissionRepository,
    private val toastRepository: ToastRepository,
    private val bridge: Bridge,
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val storageId: StorageId = StorageId(savedStateHandle["id"]!!)
    private val restoredPath = savedStateHandle[STATE_CURRENT_PATH] ?: "/"
    private val restoredScrollSnapshot = BrowserScrollSnapshot(
        index = savedStateHandle[STATE_SCROLL_INDEX] ?: 0,
        offset = savedStateHandle[STATE_SCROLL_OFFSET] ?: 0,
    )
    private val restoredSelectedPaths = (savedStateHandle.get<ArrayList<String>>(STATE_SELECTED_PATHS)
        ?: arrayListOf())
    private val _selected = MutableStateFlow(
        restoredSelectedPaths.fold(persistentHashSetOf<String>()) { acc, path ->
            acc.add(path)
        }
    )
    private val _selectMode = MutableStateFlow(savedStateHandle[STATE_SELECT_MODE] ?: false)
    private val _working = MutableStateFlow(false)
    private val _exitPage = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _storage = storageRepository.storages.map { storages ->
        storages.find { it.id == storageId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

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

    private val prefetchCache = PlaybackCache.getCache(appContext)
    private val folderPrefetcher = FolderPrefetcher(
        prefetchCache,
        PlaybackCache.buildCacheDataSourceFactory(
            appContext,
            PlaybackDataSourceFactory.create(
                bridge = bridge,
                scope = viewModelScope,
                sourceTag = PLAYBACK_SOURCE_TAG_FOLDER_PREFETCH,
            )
        ),
        viewModelScope
    )

    val splitPaths = browser.splitPaths
    val currentScrollSnapshot = browser.currentScrollSnapshot
    val currentPath = browser.currentPath
    val entries = browser.entries
    val selected = _selected.asStateFlow()
    val selectMode = _selectMode.asStateFlow()
    val loadState = browser.loadState
    val isRefreshing = browser.isRefreshing
    val working = _working.asStateFlow()
    val storage = _storage
    val canNavigateUp = browser.canNavigateUp
    val exitPage = _exitPage.asSharedFlow()
    val selectedCount = _selected.combine(entries) { selected, currentEntries ->
        currentEntries.count { entry -> selected.contains(entry.path) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val disableToggleAll = entries.map { currentEntries ->
        currentEntries.none { it.entryTyp() == StorageEntryType.MUSIC || it.isDir }
    }.stateIn(viewModelScope, SharingStarted.Lazily, true)

    init {
        viewModelScope.launch {
            var hasBoundStorage = false
            storageRepository.storages.collectLatest { storages ->
                val storage = storages.find { it.id == storageId }
                if (storage == null) {
                    browser.setStorage(null)
                    if (hasBoundStorage || storages.isNotEmpty()) {
                        toastRepository.emitToast("当前设备已不存在")
                        _exitPage.tryEmit(Unit)
                    }
                    return@collectLatest
                }
                browser.setStorage(storage.toBrowserContext())
                if (!hasBoundStorage) {
                    browser.restorePath(restoredPath)
                    browser.restoreCurrentScrollSnapshot(restoredScrollSnapshot)
                    browser.refresh(forceRemote = false)
                    hasBoundStorage = true
                } else {
                    browser.refresh(forceRemote = true)
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

    fun clickEntry(entry: StorageEntry, playlistPrefix: String, rootName: String) {
        when {
            entry.isDir -> navigateDir(entry.path)
            entry.entryTyp() == StorageEntryType.MUSIC -> playFromFolder(entry, playlistPrefix, rootName)
            else -> toastRepository.emitToast("该文件暂不支持播放")
        }
    }

    fun toggleSelectMode() {
        val next = !_selectMode.value
        if (!next) {
            clearSelection()
        }
        _selectMode.value = next
        persistSelectMode()
    }

    fun exitSelectMode() {
        _selectMode.value = false
        persistSelectMode()
        clearSelection()
    }

    fun toggleSelect(entry: StorageEntry) {
        if (!_selectMode.value) {
            return
        }
        if (!(entry.isDir || entry.entryTyp() == StorageEntryType.MUSIC)) {
            return
        }
        val path = entry.path
        val selected = _selected.value
        val next = if (selected.contains(path)) {
            selected.remove(path)
        } else {
            selected.add(path)
        }
        _selected.value = next
        persistSelectedPaths()
    }

    fun toggleAll() {
        if (!_selectMode.value) {
            return
        }
        val selectable = entries.value.filter { it.isDir || it.entryTyp() == StorageEntryType.MUSIC }
        val allSelected = _selected.value.size == selectable.size
        _selected.value = if (allSelected) {
            _selected.value.clear()
        } else {
            selectable.fold(persistentHashSetOf<String>()) { acc, entry ->
                acc.add(entry.path)
            }
        }
        persistSelectedPaths()
    }

    fun clearSelection() {
        _selected.value = _selected.value.clear()
        persistSelectedPaths()
    }

    fun navigateDir(path: String) {
        val normalized = normalizeBrowserPath(path)
        if (browser.currentPathValue() == normalized) {
            return
        }
        folderPrefetcher.cancel()
        exitSelectMode()
        browser.navigateTo(normalized)
    }

    fun navigateUp() {
        if (browser.currentPathValue() == "/") {
            return
        }
        folderPrefetcher.cancel()
        exitSelectMode()
        browser.navigateUp()
    }

    fun updateCurrentScrollSnapshot(index: Int, offset: Int) {
        browser.updateScrollSnapshot(BrowserScrollSnapshot(index = index, offset = offset))
    }

    fun requestPermission() {
        permissionRepository.requestStoragePermission()
    }

    suspend fun collectSelectedMusicEntries(): List<StorageEntry> {
        if (_selected.value.isEmpty()) {
            return emptyList()
        }
        _working.value = true
        try {
            return StorageBrowserUtils.resolveSelectedMusicEntries(
                selectedPaths = _selected.value,
                currentEntries = entries.value,
                listChildren = { dir -> listEntries(dir) }
            )
        } finally {
            _working.value = false
        }
    }

    fun playFromFolder(entry: StorageEntry, playlistPrefix: String, rootName: String) {
        viewModelScope.launch {
            _working.value = true
            try {
                val folderPath = parentBrowserPath(entry.path)
                val folderEntries = if (folderPath == browser.currentPathValue()) {
                    entries.value
                } else {
                    listEntries(folderPath) ?: return@launch
                }
                val songs = folderEntries.filter { it.entryTyp() == StorageEntryType.MUSIC }
                if (songs.isEmpty()) {
                    toastRepository.emitToast("当前文件夹暂无可播放的音乐")
                    return@launch
                }

                val playlistName = StorageBrowserUtils.buildFolderPlaylistName(
                    folderPath,
                    playlistPrefix,
                    rootName
                )
                val existing = playlistRepository.playlists.value.find { it.meta.title == playlistName }
                if (existing != null) {
                    bridge.run { backend -> ctRemovePlaylist(backend, existing.meta.id) }
                }

                val entries = songs.map { song -> ToAddMusicEntry(song, song.name) }
                val created = bridge.run { backend ->
                    ctCreatePlaylist(
                        backend,
                        ArgCreatePlaylist(
                            title = playlistName,
                            cover = null,
                            entries = entries
                        )
                    )
                }
                if (created == null) {
                    toastRepository.emitToast("创建播放列表失败")
                    return@launch
                }
                val index = songs.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
                val musicId = created.musicIds.getOrNull(index)?.id
                    ?: created.musicIds.firstOrNull()?.id
                if (musicId == null) {
                    toastRepository.emitToast("播放列表为空")
                    return@launch
                }
                folderPrefetcher.cancel()
                prefetchFolderSongs(
                    songs = songs,
                    musicIds = created.musicIds,
                    startIndex = index,
                )
                playlistRepository.reload()
                playerControllerRepository.play(musicId, created.id)
            } finally {
                _working.value = false
            }
        }
    }

    fun reload() {
        browser.refresh(forceRemote = true)
    }

    private suspend fun listEntries(path: String): List<StorageEntry>? {
        return when (val result = browser.readDirectory(path)) {
            is DirectoryFetchResult.Success -> result.value.entries
            is DirectoryFetchResult.Failure -> {
                emitLoadFailureToast(result.value.state)
                null
            }
        }
    }

    private fun prefetchFolderSongs(
        songs: List<StorageEntry>,
        musicIds: List<uniffi.ease_client_backend.AddedMusic>,
        startIndex: Int
    ) {
        if (songs.isEmpty() || musicIds.isEmpty()) {
            return
        }
        val tasks = mutableListOf<Pair<android.net.Uri, Long>>()
        val count = min(songs.size, musicIds.size)
        if (count == 0) {
            return
        }
        val safeStart = startIndex.coerceIn(0, count - 1)
        val endExclusive = min(count, safeStart + MAX_FOLDER_PREFETCH)
        for (index in safeStart until endExclusive) {
            if (index == safeStart) {
                continue
            }
            val size = songs[index].size?.toLong() ?: continue
            val bytes = min(size, PlaybackCachePolicy.prefetchBytesByPercent(size, 0.1f))
            if (bytes <= 0) {
                continue
            }
            val uri = buildPlaybackMusicUri(musicIds[index].id)
            tasks.add(uri to bytes)
        }
        folderPrefetcher.prefetch(tasks)
    }

    private fun trimSelectionToEntries(currentEntries: List<StorageEntry>) {
        val selectablePaths = currentEntries
            .filter { it.isDir || it.entryTyp() == StorageEntryType.MUSIC }
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

    private fun persistSelectedPaths() {
        savedSelectedPaths = ArrayList(_selected.value)
    }

    private fun persistSelectMode() {
        savedSelectMode = _selectMode.value
    }

    private fun Storage.toBrowserContext(): BrowserStorageContext {
        return BrowserStorageContext(
            storageId = id,
            isLocal = typ == StorageType.LOCAL,
        )
    }

    override fun onCleared() {
        super.onCleared()
        folderPrefetcher.cancel()
    }

    private var savedSelectedPaths: ArrayList<String>
        get() = ArrayList(_selected.value)
        set(value) {
            savedStateHandle[STATE_SELECTED_PATHS] = value
        }

    private var savedSelectMode: Boolean
        get() = _selectMode.value
        set(value) {
            savedStateHandle[STATE_SELECT_MODE] = value
        }
}
