package com.kutedev.easemusicplayer.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import com.kutedev.easemusicplayer.core.FolderPrefetcher
import com.kutedev.easemusicplayer.core.MusicPlayerDataSource
import com.kutedev.easemusicplayer.core.PlaybackCache
import com.kutedev.easemusicplayer.core.PlaybackCachePolicy
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.PermissionRepository
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import kotlin.math.min

data class BrowserPathItem(
    val path: String,
    val name: String,
)

@HiltViewModel
class StorageBrowserVM @Inject constructor(
    private val storageRepository: StorageRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    private val permissionRepository: PermissionRepository,
    private val toastRepository: ToastRepository,
    private val bridge: Bridge,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val storageId: StorageId = StorageId(savedStateHandle["id"]!!)
    private val _currentPath = MutableStateFlow("/")
    private val _splitPaths = _currentPath.map { path ->
        val components = path.split('/').filter { it.isNotEmpty() }
        val splitPaths = mutableListOf<BrowserPathItem>()

        var currentPath = ""
        for (component in components) {
            currentPath = if (currentPath == "/") {
                "/$component"
            } else {
                "$currentPath/$component"
            }
            val name = try {
                URLDecoder.decode(component, "UTF-8")
            } catch (e: Exception) {
                component
            }
            splitPaths.add(BrowserPathItem(currentPath, name))
        }
        splitPaths
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _selected = MutableStateFlow(persistentHashSetOf<String>())
    private val _selectMode = MutableStateFlow(false)
    private val _entries = MutableStateFlow(listOf<StorageEntry>())
    private val _loadState = MutableStateFlow(CurrentStorageStateType.LOADING)
    private val _working = MutableStateFlow(false)
    private val _undoStack = MutableStateFlow(persistentListOf<String>())
    private val _storage = storageRepository.storages.map { storages ->
        storages.find { it.id == storageId }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    private val folderPrefetcher = FolderPrefetcher(
        PlaybackCache.buildCacheDataSourceFactory(
            appContext,
            DataSource.Factory { MusicPlayerDataSource(bridge, viewModelScope) }
        ),
        viewModelScope
    )

    val splitPaths = _splitPaths
    val entries = _entries.asStateFlow()
    val selected = _selected.asStateFlow()
    val selectMode = _selectMode.asStateFlow()
    val loadState = _loadState.asStateFlow()
    val working = _working.asStateFlow()
    val storage = _storage
    val canUndo = _undoStack.map { undoStack ->
        undoStack.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val selectedCount = _selected.combine(_entries) { selected, entries ->
        entries.count { entry -> selected.contains(entry.path) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val disableToggleAll = _entries.map { entries ->
        entries.none { it.entryTyp() == StorageEntryType.MUSIC || it.isDir }
    }.stateIn(viewModelScope, SharingStarted.Lazily, true)

    init {
        viewModelScope.launch {
            storageRepository.storages.collect {
                reload()
            }
        }
        viewModelScope.launch {
            permissionRepository.havePermission.collect {
                reload()
            }
        }
        viewModelScope.launch {
            reload()
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
    }

    fun exitSelectMode() {
        _selectMode.value = false
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
    }

    fun toggleAll() {
        if (!_selectMode.value) {
            return
        }
        val selectable = _entries.value.filter { it.isDir || it.entryTyp() == StorageEntryType.MUSIC }
        val allSelected = _selected.value.size == selectable.size
        if (allSelected) {
            _selected.value = _selected.value.clear()
        } else {
            _selected.value = _selected.value.clear().addAll(selectable.map { it.path })
        }
    }

    fun clearSelection() {
        _selected.value = _selected.value.clear()
    }

    fun navigateDir(path: String) {
        pushCurrentToUndoStack()
        navigateDirImpl(path)
    }

    fun undo() {
        val current = popCurrentFromUndoStack()
        if (current != null) {
            navigateDirImpl(current)
        }
    }

    private fun navigateDirImpl(path: String) {
        _currentPath.value = path
        folderPrefetcher.cancel()
        exitSelectMode()
        reload()
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
                currentEntries = _entries.value,
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
                val folderPath = parentPath(entry.path)
                val folderEntries = if (folderPath == currentPath()) {
                    _entries.value
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
                prefetchFolderSongs(songs, created.musicIds)
                playlistRepository.requestTotalDuration(appContext, created.musicIds)
                playlistRepository.reload()
                playerControllerRepository.play(musicId, created.id)
            } finally {
                _working.value = false
            }
        }
    }

    fun reload() {
        val storage = currentStorage() ?: return

        if (storage.typ == StorageType.LOCAL && !permissionRepository.havePermission.value) {
            _loadState.value = CurrentStorageStateType.NEED_PERMISSION
            return
        }

        _loadState.value = CurrentStorageStateType.LOADING
        _entries.value = emptyList()

        viewModelScope.launch {
            val resp = bridge.runRaw {
                ctListStorageEntryChildren(
                    it,
                    StorageEntryLoc(
                        storageId = storage.id,
                        path = currentPath()
                    )
                )
            }

            when (resp) {
                is ListStorageEntryChildrenResp.Ok -> {
                    _loadState.value = CurrentStorageStateType.OK
                    _entries.value = resp.v1
                }
                ListStorageEntryChildrenResp.AuthenticationFailed -> {
                    _loadState.value = CurrentStorageStateType.AUTHENTICATION_FAILED
                }
                ListStorageEntryChildrenResp.Timeout -> {
                    _loadState.value = CurrentStorageStateType.TIMEOUT
                }
                ListStorageEntryChildrenResp.Unknown -> {
                    _loadState.value = CurrentStorageStateType.UNKNOWN_ERROR
                }
            }
        }
    }

    private suspend fun listEntries(path: String): List<StorageEntry>? {
        val resp = bridge.runRaw {
            ctListStorageEntryChildren(
                it,
                StorageEntryLoc(
                    storageId = storageId,
                    path = path
                )
            )
        }
        return when (resp) {
            is ListStorageEntryChildrenResp.Ok -> resp.v1
            ListStorageEntryChildrenResp.AuthenticationFailed -> {
                toastRepository.emitToast("认证失败，请检查设备配置")
                null
            }
            ListStorageEntryChildrenResp.Timeout -> {
                toastRepository.emitToast("连接超时，请重试")
                null
            }
            ListStorageEntryChildrenResp.Unknown -> {
                toastRepository.emitToast("加载失败，请重试")
                null
            }
        }
    }

    private fun currentPath(): String {
        return _currentPath.value
    }

    private fun currentStorage(): Storage? {
        return storageRepository.storages.value.find { it.id == storageId }
    }

    private fun prefetchFolderSongs(songs: List<StorageEntry>, musicIds: List<uniffi.ease_client_backend.AddedMusic>) {
        if (songs.isEmpty() || musicIds.isEmpty()) {
            return
        }
        val tasks = mutableListOf<Pair<android.net.Uri, Long>>()
        val count = min(songs.size, musicIds.size)
        for (index in 0 until count) {
            val size = songs[index].size?.toLong() ?: continue
            val bytes = PlaybackCachePolicy.prefetchBytesByPercent(size, 0.1f)
            if (bytes <= 0) {
                continue
            }
            val id = musicIds[index].id.value
            val uri = android.net.Uri.parse("ease://data?music=$id")
            tasks.add(uri to bytes)
        }
        folderPrefetcher.prefetch(tasks)
    }

    private fun pushCurrentToUndoStack() {
        val currentUndoStack = _undoStack.value
        val nextUndoStack = currentUndoStack.add(currentPath())
        _undoStack.value = nextUndoStack
    }

    private fun popCurrentFromUndoStack(): String? {
        val currentUndoStack = _undoStack.value
        val current = currentUndoStack.lastOrNull()
        if (current != null) {
            val next = currentUndoStack.removeAt(currentUndoStack.lastIndex)
            _undoStack.value = next
        }
        return current
    }

    private fun parentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    override fun onCleared() {
        super.onCleared()
        folderPrefetcher.cancel()
    }
}
