package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.ToastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.ArgUpsertStorage
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.StorageConnectionTestResult
import uniffi.ease_client_backend.ctListStorageEntryChildrenByArg
import uniffi.ease_client_backend.ctTestStorage
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType

private const val STATE_PICKER_OPEN = "edit_storage_openlist_picker_open"
private const val STATE_PICKER_CURRENT_PATH = "edit_storage_openlist_picker_current_path"
private const val STATE_PICKER_SCROLL_INDEX = "edit_storage_openlist_picker_scroll_index"
private const val STATE_PICKER_SCROLL_OFFSET = "edit_storage_openlist_picker_scroll_offset"
private const val STATE_PICKER_SESSION_ID = "edit_storage_openlist_picker_session_id"
private const val STATE_PICKER_SESSION_SEED = "edit_storage_openlist_picker_session_seed"
private const val STATE_PICKER_ADDR = "edit_storage_openlist_picker_addr"
private const val STATE_PICKER_ALIAS = "edit_storage_openlist_picker_alias"
private const val STATE_PICKER_USERNAME = "edit_storage_openlist_picker_username"
private const val STATE_PICKER_PASSWORD = "edit_storage_openlist_picker_password"
private const val STATE_PICKER_IS_ANONYMOUS = "edit_storage_openlist_picker_is_anonymous"
private const val STATE_PICKER_DEFAULT_PATH = "edit_storage_openlist_picker_default_path"
private const val STATE_PICKER_STORAGE_VALUE = "edit_storage_openlist_picker_storage_value"

private fun normalizeStorageDefaultPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) {
        return "/"
    }
    return normalizeBrowserPath(trimmed)
}

data class Validated(
    val addrEmpty: Boolean = false,
    val aliasEmpty: Boolean = false,
    val usernameEmpty: Boolean = false,
    val passwordEmpty: Boolean = false,
) {
    fun valid(): Boolean {
        return !addrEmpty && !aliasEmpty && !usernameEmpty && !passwordEmpty
    }
}

private fun defaultArgUpsertStorage(): ArgUpsertStorage {
    return ArgUpsertStorage(
        id = null,
        addr = "",
        alias = "",
        username = "",
        password = "",
        isAnonymous = true,
        typ = StorageType.WEBDAV,
        defaultPath = "/",
    )
}

private fun SavedStateHandle.restoreOpenListPickerSnapshot(): ArgUpsertStorage? {
    val addr = get<String>(STATE_PICKER_ADDR) ?: return null
    return ArgUpsertStorage(
        id = get<Long>(STATE_PICKER_STORAGE_VALUE)?.let(::StorageId),
        addr = addr,
        alias = get<String>(STATE_PICKER_ALIAS) ?: "",
        username = get<String>(STATE_PICKER_USERNAME) ?: "",
        password = get<String>(STATE_PICKER_PASSWORD) ?: "",
        isAnonymous = get<Boolean>(STATE_PICKER_IS_ANONYMOUS) ?: true,
        typ = StorageType.OPEN_LIST,
        defaultPath = normalizeStorageDefaultPath(get<String>(STATE_PICKER_DEFAULT_PATH) ?: "/"),
    )
}

@HiltViewModel
class EditStorageVM @Inject constructor(
    private val bridge: Bridge,
    private val storageRepository: StorageRepository,
    private val toastRepository: ToastRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val restoredPickerSnapshot = savedStateHandle.restoreOpenListPickerSnapshot()
    private val restoredPickerPath =
        savedStateHandle[STATE_PICKER_CURRENT_PATH] ?: restoredPickerSnapshot?.defaultPath ?: "/"
    private val restoredPickerScrollSnapshot = BrowserScrollSnapshot(
        index = savedStateHandle[STATE_PICKER_SCROLL_INDEX] ?: 0,
        offset = savedStateHandle[STATE_PICKER_SCROLL_OFFSET] ?: 0,
    )
    private val restoredPickerOpen =
        savedStateHandle.get<Boolean>(STATE_PICKER_OPEN) ?: false
    private val restoredPickerSessionId = savedStateHandle.get<Long>(STATE_PICKER_SESSION_ID) ?: 0L

    private val _title = MutableStateFlow("")
    private val _musicCount = MutableStateFlow(0uL)
    private val _form = MutableStateFlow(defaultArgUpsertStorage())
    private var _formBackups = HashMap<StorageType, ArgUpsertStorage>()

    private val _validated = MutableStateFlow(Validated())
    private val _removeModalOpen = MutableStateFlow(false)
    private val _testResult = MutableStateFlow(StorageConnectionTestResult.NONE)
    private val _openListPickerSnapshot = MutableStateFlow(restoredPickerSnapshot)
    private val _defaultPathPickerOpen = MutableStateFlow(restoredPickerOpen && restoredPickerSnapshot != null)
    private var _testJob: Job? = null
    private var pickerSessionSeed = savedStateHandle.get<Long>(STATE_PICKER_SESSION_SEED) ?: 0L

    private val defaultPathPickerBrowser = DirectoryBrowserController(
        scope = viewModelScope,
        initialPath = restoredPickerPath,
        initialScrollSnapshot = restoredPickerScrollSnapshot,
        listEntriesRemote = { _, path ->
            listOpenListPickerEntries(path)
        },
        hasLocalPermission = { true },
        onPersistPath = { savedStateHandle[STATE_PICKER_CURRENT_PATH] = it },
        onPersistScrollSnapshot = { snapshot ->
            savedStateHandle[STATE_PICKER_SCROLL_INDEX] = snapshot.index
            savedStateHandle[STATE_PICKER_SCROLL_OFFSET] = snapshot.offset
        },
        onBackgroundRefreshFailed = { state ->
            emitDefaultPathPickerLoadFailureToast(state)
        }
    )

    val form = _form.asStateFlow()
    val musicCount = _musicCount.asStateFlow()
    val title = _title.asStateFlow()
    val validated = _validated.asStateFlow()
    val removeModalOpen = _removeModalOpen.asStateFlow()
    val isCreated = form.map { currentForm -> currentForm.id == null }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)
    val testResult = _testResult.asStateFlow()
    val defaultPathPickerOpen = _defaultPathPickerOpen.asStateFlow()
    val defaultPathPickerCurrentPath = defaultPathPickerBrowser.currentPath
    val defaultPathPickerSplitPaths = defaultPathPickerBrowser.splitPaths
    val defaultPathPickerEntries = defaultPathPickerBrowser.entries
        .map { entries -> entries.filter { entry -> entry.isDir } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val defaultPathPickerLoadState = defaultPathPickerBrowser.loadState
    val defaultPathPickerIsRefreshing = defaultPathPickerBrowser.isRefreshing
    val defaultPathPickerScrollSnapshot = defaultPathPickerBrowser.currentScrollSnapshot

    init {
        viewModelScope.launch {
            storageRepository.oauthRefreshToken.collect { refreshToken ->
                updateForm { storage ->
                    if (storage.typ == StorageType.ONE_DRIVE) {
                        storage.password = refreshToken
                    }
                    storage
                }
            }
        }

        _form.value = defaultArgUpsertStorage()
        _title.value = ""
        _musicCount.value = 0u

        val id: Long? = savedStateHandle["id"]
        val storage = storageRepository.storages.value.find { v -> id != null && v.id == StorageId(id) }
        if (storage != null) {
            _form.value = ArgUpsertStorage(
                id = storage.id,
                addr = storage.addr,
                alias = storage.alias,
                username = storage.username,
                password = storage.password,
                isAnonymous = storage.isAnonymous,
                typ = storage.typ,
                defaultPath = storage.defaultPath,
            )
            _title.value = VImportStorageEntry(storage).name
            _musicCount.value = storage.musicCount
        }

        if (_defaultPathPickerOpen.value && restoredPickerSnapshot != null) {
            val sessionId = restoredPickerSessionId.takeIf { it != 0L } ?: nextPickerSessionId()
            savedStateHandle[STATE_PICKER_SESSION_ID] = sessionId
            bindDefaultPathPickerSession(
                sessionId = sessionId,
                path = restoredPickerPath,
                scrollSnapshot = restoredPickerScrollSnapshot,
                forceRemote = false,
            )
        }
    }

    fun test() {
        resetTestResult()
        if (!validate()) {
            return
        }
        val normalizedForm = normalizedFormSnapshot()
        _form.value = normalizedForm
        _testResult.value = StorageConnectionTestResult.TESTING

        _testJob = viewModelScope.launch {
            _testResult.value = bridge.runRaw { ctTestStorage(it, normalizedForm) }
            sendTestToast()

            delay(5000)
            resetTestResult()
        }
    }

    fun openDefaultPathPicker() {
        if (form.value.typ != StorageType.OPEN_LIST) {
            return
        }
        if (!validate()) {
            return
        }
        val normalizedForm = normalizedFormSnapshot()
        _form.value = normalizedForm
        val sessionId = nextPickerSessionId()
        _openListPickerSnapshot.value = normalizedForm.copy()
        persistOpenListPickerSnapshot(_openListPickerSnapshot.value)
        _defaultPathPickerOpen.value = true
        savedStateHandle[STATE_PICKER_OPEN] = true
        savedStateHandle[STATE_PICKER_SESSION_ID] = sessionId
        bindDefaultPathPickerSession(
            sessionId = sessionId,
            path = normalizedForm.defaultPath,
            scrollSnapshot = BrowserScrollSnapshot(),
            forceRemote = true,
        )
    }

    fun closeDefaultPathPicker() {
        _defaultPathPickerOpen.value = false
        savedStateHandle[STATE_PICKER_OPEN] = false
        savedStateHandle[STATE_PICKER_SESSION_ID] = 0L
        _openListPickerSnapshot.value = null
        persistOpenListPickerSnapshot(null)
        defaultPathPickerBrowser.clearVisibleState()
    }

    fun confirmDefaultPathPickerSelection() {
        val selectedPath = normalizeStorageDefaultPath(defaultPathPickerBrowser.currentPathValue())
        updateForm { storage ->
            storage.defaultPath = selectedPath
            storage
        }
        closeDefaultPathPicker()
    }

    fun navigateDefaultPathPickerDir(path: String) {
        defaultPathPickerBrowser.navigateTo(normalizeStorageDefaultPath(path))
    }

    fun reloadDefaultPathPicker() {
        defaultPathPickerBrowser.refresh(forceRemote = true)
    }

    fun updateDefaultPathPickerScrollSnapshot(index: Int, offset: Int) {
        defaultPathPickerBrowser.updateScrollSnapshot(
            BrowserScrollSnapshot(index = index, offset = offset)
        )
    }

    private suspend fun listOpenListPickerEntries(path: String): ListStorageEntryChildrenResp {
        val snapshot = _openListPickerSnapshot.value ?: return ListStorageEntryChildrenResp.Unknown
        return bridge.runRaw {
            ctListStorageEntryChildrenByArg(
                it,
                snapshot,
                normalizeStorageDefaultPath(path),
            )
        }
    }

    private fun bindDefaultPathPickerSession(
        sessionId: Long,
        path: String,
        scrollSnapshot: BrowserScrollSnapshot,
        forceRemote: Boolean,
    ) {
        defaultPathPickerBrowser.setStorage(
            BrowserStorageContext(
                storageId = StorageId(sessionId),
                isLocal = false,
            )
        )
        defaultPathPickerBrowser.restorePath(normalizeStorageDefaultPath(path))
        defaultPathPickerBrowser.restoreCurrentScrollSnapshot(scrollSnapshot)
        defaultPathPickerBrowser.refresh(forceRemote = forceRemote)
    }

    private fun nextPickerSessionId(): Long {
        pickerSessionSeed -= 1L
        savedStateHandle[STATE_PICKER_SESSION_SEED] = pickerSessionSeed
        return pickerSessionSeed
    }

    private fun persistOpenListPickerSnapshot(snapshot: ArgUpsertStorage?) {
        savedStateHandle[STATE_PICKER_ADDR] = snapshot?.addr
        savedStateHandle[STATE_PICKER_ALIAS] = snapshot?.alias
        savedStateHandle[STATE_PICKER_USERNAME] = snapshot?.username
        savedStateHandle[STATE_PICKER_PASSWORD] = snapshot?.password
        savedStateHandle[STATE_PICKER_IS_ANONYMOUS] = snapshot?.isAnonymous
        savedStateHandle[STATE_PICKER_DEFAULT_PATH] = snapshot?.defaultPath
        savedStateHandle[STATE_PICKER_STORAGE_VALUE] = snapshot?.id?.value
    }

    private fun normalizedFormSnapshot(): ArgUpsertStorage {
        val normalized = form.value.copy()
        normalized.defaultPath = normalizeStorageDefaultPath(normalized.defaultPath)
        return normalized
    }

    private fun emitDefaultPathPickerLoadFailureToast(state: CurrentStorageStateType) {
        when (state) {
            CurrentStorageStateType.AUTHENTICATION_FAILED -> {
                toastRepository.emitToast("认证失败，请检查 OpenList 配置")
            }

            CurrentStorageStateType.TIMEOUT -> {
                toastRepository.emitToast("目录加载超时，请重试")
            }

            CurrentStorageStateType.UNKNOWN_ERROR -> {
                toastRepository.emitToast("目录加载失败，请重试")
            }

            else -> Unit
        }
    }

    private fun sendTestToast() {
        val testing = _testResult.value
        if (testing == StorageConnectionTestResult.NONE || testing == StorageConnectionTestResult.TESTING) {
            return
        }

        when (testing) {
            StorageConnectionTestResult.SUCCESS -> {
                toastRepository.emitToastRes(R.string.storage_edit_testing_toast_success)
            }
            StorageConnectionTestResult.TIMEOUT -> {
                toastRepository.emitToastRes(R.string.storage_edit_testing_toast_timeout)
            }
            StorageConnectionTestResult.UNAUTHORIZED -> {
                toastRepository.emitToastRes(R.string.storage_edit_testing_toast_unauth)
            }
            StorageConnectionTestResult.OTHER_ERROR -> {
                toastRepository.emitToastRes(R.string.storage_edit_testing_toast_other_error)
            }
            else -> {}
        }
    }

    fun openRemoveModal() {
        _removeModalOpen.value = true
    }

    fun closeRemoveModal() {
        _removeModalOpen.value = false
    }

    fun updateForm(block: (form: ArgUpsertStorage) -> ArgUpsertStorage) {
        _form.value = block(form.value.copy())
    }

    fun changeType(typ: StorageType) {
        _formBackups[_form.value.typ] = _form.value.copy()

        val backup = _formBackups[typ]
        if (backup != null) {
            _form.value = backup
        } else {
            val isAnonymous = when (typ) {
                StorageType.OPEN_LIST -> true
                StorageType.WEBDAV -> false
                else -> false
            }
            val newForm = ArgUpsertStorage(
                id = _form.value.id,
                addr = "",
                alias = _form.value.alias,
                username = "",
                password = "",
                isAnonymous = isAnonymous,
                typ = typ,
                defaultPath = "/",
            )
            _form.value = newForm
        }
        _validated.value = Validated()
    }

    private fun validate(): Boolean {
        val f = form.value
        val useAddr = f.typ == StorageType.WEBDAV || f.typ == StorageType.OPEN_LIST
        val useAlias = f.typ == StorageType.ONE_DRIVE
        val needAuth = (f.typ == StorageType.WEBDAV || f.typ == StorageType.OPEN_LIST) && !f.isAnonymous
        _validated.value = Validated(
            addrEmpty = if (useAddr) f.addr.isBlank() else false,
            aliasEmpty = if (useAlias) f.alias.isBlank() else false,
            usernameEmpty = if (needAuth) f.username.isBlank() else false,
            passwordEmpty = when (f.typ) {
                StorageType.ONE_DRIVE -> f.password.isBlank()
                StorageType.WEBDAV, StorageType.OPEN_LIST -> needAuth && f.password.isBlank()
                else -> false
            },
        )
        return _validated.value.valid()
    }

    fun remove() {
        val id = _form.value.id

        if (id != null) {
            viewModelScope.launch {
                storageRepository.remove(id)
            }
        }
    }

    suspend fun finish(): Boolean {
        if (!validate()) {
            return false
        }

        val normalizedForm = normalizedFormSnapshot()
        _form.value = normalizedForm
        storageRepository.upsertStorage(normalizedForm)
        return true
    }

    fun resetDefaultPathToRoot() {
        updateForm { storage ->
            storage.defaultPath = "/"
            storage
        }
    }

    private fun resetTestResult() {
        _testJob?.cancel()
        _testJob = null
        _testResult.value = StorageConnectionTestResult.NONE
    }
}
