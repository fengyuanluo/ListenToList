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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

private const val STATE_FORM_TYPE = "edit_storage_form_type"
private const val STATE_FORM_ADDR = "edit_storage_form_addr"
private const val STATE_FORM_ALIAS = "edit_storage_form_alias"
private const val STATE_FORM_USERNAME = "edit_storage_form_username"
private const val STATE_FORM_PASSWORD = "edit_storage_form_password"
private const val STATE_FORM_IS_ANONYMOUS = "edit_storage_form_is_anonymous"
private const val STATE_FORM_DEFAULT_PATH = "edit_storage_form_default_path"
private const val STATE_FORM_STORAGE_VALUE = "edit_storage_form_storage_value"
private const val STATE_DEFAULT_PATH_BROWSER_EXPANDED = "edit_storage_default_path_browser_expanded"
private const val STATE_DEFAULT_PATH_BROWSER_CURRENT_PATH = "edit_storage_default_path_browser_current_path"
private const val STATE_DEFAULT_PATH_BROWSER_SCROLL_INDEX = "edit_storage_default_path_browser_scroll_index"
private const val STATE_DEFAULT_PATH_BROWSER_SCROLL_OFFSET = "edit_storage_default_path_browser_scroll_offset"
private const val DEFAULT_PATH_BROWSER_STORAGE_VALUE = -1L

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

private data class DefaultPathBrowserConfig(
    val typ: StorageType,
    val addr: String,
    val username: String,
    val password: String,
    val isAnonymous: Boolean,
)

private data class DefaultPathBrowserBinding(
    val expanded: Boolean,
    val config: DefaultPathBrowserConfig?,
)

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

private fun SavedStateHandle.restoreEditStorageForm(): ArgUpsertStorage? {
    val typRaw = get<String>(STATE_FORM_TYPE) ?: return null
    val typ = runCatching { StorageType.valueOf(typRaw) }.getOrDefault(StorageType.WEBDAV)
    return ArgUpsertStorage(
        id = get<Long>(STATE_FORM_STORAGE_VALUE)?.let(::StorageId),
        addr = get<String>(STATE_FORM_ADDR) ?: "",
        alias = get<String>(STATE_FORM_ALIAS) ?: "",
        username = get<String>(STATE_FORM_USERNAME) ?: "",
        password = get<String>(STATE_FORM_PASSWORD) ?: "",
        isAnonymous = get<Boolean>(STATE_FORM_IS_ANONYMOUS)
            ?: if (typ == StorageType.OPEN_LIST) true else false,
        typ = typ,
        defaultPath = normalizeStorageDefaultPath(get<String>(STATE_FORM_DEFAULT_PATH) ?: "/"),
    )
}

private fun ArgUpsertStorage.defaultPathBrowserConfigOrNull(): DefaultPathBrowserConfig? {
    if (!supportsStorageDefaultPath()) {
        return null
    }

    val ready = when (typ) {
        StorageType.OPEN_LIST,
        StorageType.WEBDAV -> {
            addr.isNotBlank() && (isAnonymous || (username.isNotBlank() && password.isNotBlank()))
        }

        StorageType.ONE_DRIVE -> password.isNotBlank()
        StorageType.LOCAL -> false
    }
    if (!ready) {
        return null
    }

    return DefaultPathBrowserConfig(
        typ = typ,
        addr = addr.trim(),
        username = if (isAnonymous) "" else username,
        password = if (isAnonymous) "" else password,
        isAnonymous = isAnonymous,
    )
}

@HiltViewModel
@OptIn(FlowPreview::class)
class EditStorageVM @Inject constructor(
    private val bridge: Bridge,
    private val storageRepository: StorageRepository,
    private val toastRepository: ToastRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val restoredFormDraft = savedStateHandle.restoreEditStorageForm()
    private val restoredDefaultPathBrowserPath =
        savedStateHandle[STATE_DEFAULT_PATH_BROWSER_CURRENT_PATH]
            ?: restoredFormDraft?.defaultPath
            ?: "/"
    private val restoredDefaultPathBrowserScrollSnapshot = BrowserScrollSnapshot(
        index = savedStateHandle[STATE_DEFAULT_PATH_BROWSER_SCROLL_INDEX] ?: 0,
        offset = savedStateHandle[STATE_DEFAULT_PATH_BROWSER_SCROLL_OFFSET] ?: 0,
    )

    private val _title = MutableStateFlow("")
    private val _musicCount = MutableStateFlow(0uL)
    private val _form = MutableStateFlow(defaultArgUpsertStorage())
    private var _formBackups = HashMap<StorageType, ArgUpsertStorage>()

    private val _validated = MutableStateFlow(Validated())
    private val _removeModalOpen = MutableStateFlow(false)
    private val _testResult = MutableStateFlow(StorageConnectionTestResult.NONE)
    private val _defaultPathBrowserExpanded = MutableStateFlow(
        savedStateHandle.get<Boolean>(STATE_DEFAULT_PATH_BROWSER_EXPANDED) ?: false
    )
    private val _defaultPathFieldError = MutableStateFlow<Int?>(null)
    private var _testJob: Job? = null
    private var boundDefaultPathBrowserConfig: DefaultPathBrowserConfig? = null

    private val defaultPathBrowser = DirectoryBrowserController(
        scope = viewModelScope,
        initialPath = restoredDefaultPathBrowserPath,
        initialScrollSnapshot = restoredDefaultPathBrowserScrollSnapshot,
        listEntriesRemote = { _, path ->
            listInlineDefaultPathEntries(path)
        },
        hasLocalPermission = { true },
        onPersistPath = { savedStateHandle[STATE_DEFAULT_PATH_BROWSER_CURRENT_PATH] = it },
        onPersistScrollSnapshot = { snapshot ->
            savedStateHandle[STATE_DEFAULT_PATH_BROWSER_SCROLL_INDEX] = snapshot.index
            savedStateHandle[STATE_DEFAULT_PATH_BROWSER_SCROLL_OFFSET] = snapshot.offset
        },
        onBackgroundRefreshFailed = { state ->
            emitDefaultPathBrowserLoadFailureToast(state)
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
    val defaultPathBrowserExpanded = _defaultPathBrowserExpanded.asStateFlow()
    val defaultPathFieldError = _defaultPathFieldError.asStateFlow()
    val defaultPathBrowserReady = form.map { currentForm ->
        currentForm.defaultPathBrowserConfigOrNull() != null
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    val defaultPathBrowserCurrentPath = defaultPathBrowser.currentPath
    val defaultPathBrowserSplitPaths = defaultPathBrowser.splitPaths
    val defaultPathBrowserEntries = defaultPathBrowser.entries
        .map { entries -> entries.filter { entry -> entry.isDir } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val defaultPathBrowserLoadState = defaultPathBrowser.loadState
    val defaultPathBrowserIsRefreshing = defaultPathBrowser.isRefreshing
    val defaultPathBrowserScrollSnapshot = defaultPathBrowser.currentScrollSnapshot

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

        val id: Long? = savedStateHandle["id"]
        val storage = storageRepository.storages.value.find { v -> id != null && v.id == StorageId(id) }
        val initialForm = restoredFormDraft ?: storage?.let { currentStorage ->
            ArgUpsertStorage(
                id = currentStorage.id,
                addr = currentStorage.addr,
                alias = currentStorage.alias,
                username = currentStorage.username,
                password = currentStorage.password,
                isAnonymous = currentStorage.isAnonymous,
                typ = currentStorage.typ,
                defaultPath = currentStorage.defaultPath,
            )
        } ?: defaultArgUpsertStorage()

        setFormValue(initialForm, persist = true)
        _title.value = storage?.let(::VImportStorageEntry)?.name ?: ""
        _musicCount.value = storage?.musicCount ?: 0uL
        if (!initialForm.typ.supportsStorageDefaultPath()) {
            setDefaultPathBrowserExpanded(false)
        }

        viewModelScope.launch {
            combine(
                _defaultPathBrowserExpanded,
                _form.map { currentForm -> currentForm.defaultPathBrowserConfigOrNull() }
                    .distinctUntilChanged(),
            ) { expanded, config ->
                DefaultPathBrowserBinding(expanded = expanded, config = config)
            }.debounce(300).collect { binding ->
                when {
                    !binding.expanded -> {
                        unbindDefaultPathBrowser()
                    }

                    binding.config == null -> {
                        unbindDefaultPathBrowser()
                    }

                    else -> {
                        bindDefaultPathBrowser(forceRemote = false)
                    }
                }
            }
        }

        if (_defaultPathBrowserExpanded.value && initialForm.defaultPathBrowserConfigOrNull() != null) {
            bindDefaultPathBrowser(
                forceRemote = false,
                preferredPath = restoredDefaultPathBrowserPath,
            )
        }
    }

    fun test() {
        resetTestResult()
        if (!validate()) {
            return
        }
        val normalizedForm = normalizedFormSnapshot()
        setFormValue(normalizedForm)
        _testResult.value = StorageConnectionTestResult.TESTING

        _testJob = viewModelScope.launch {
            _testResult.value = bridge.runRaw { ctTestStorage(it, normalizedForm) }
            sendTestToast()

            delay(5000)
            resetTestResult()
        }
    }

    fun expandDefaultPathBrowser() {
        if (!form.value.supportsStorageDefaultPath()) {
            return
        }
        val preferredPath = if (_defaultPathBrowserExpanded.value) {
            defaultPathBrowser.currentPathValue()
        } else {
            normalizeStorageDefaultPath(form.value.defaultPath)
        }
        setDefaultPathBrowserExpanded(true)
        bindDefaultPathBrowser(
            forceRemote = false,
            preferredPath = preferredPath,
        )
    }

    fun collapseDefaultPathBrowser() {
        if (!form.value.supportsStorageDefaultPath()) {
            return
        }
        setDefaultPathBrowserExpanded(false)
    }

    fun commitDefaultPathInput() {
        if (!form.value.supportsStorageDefaultPath()) {
            return
        }
        clearDefaultPathFieldError()
        val normalizedPath = normalizeStorageDefaultPath(form.value.defaultPath)
        setFormValue(form.value.copy(defaultPath = normalizedPath))
        expandDefaultPathBrowser()
        bindDefaultPathBrowser(forceRemote = true, preferredPath = normalizedPath)
    }

    fun onDefaultPathInputChange(value: String) {
        clearDefaultPathFieldError()
        updateForm { storage ->
            storage.defaultPath = value
            storage
        }
    }

    fun navigateDefaultPathBrowserDir(path: String) {
        if (!form.value.supportsStorageDefaultPath()) {
            return
        }
        clearDefaultPathFieldError()
        val normalizedPath = normalizeStorageDefaultPath(path)
        setFormValue(form.value.copy(defaultPath = normalizedPath))
        bindDefaultPathBrowser(forceRemote = false, preferredPath = normalizedPath)
    }

    fun reloadDefaultPathBrowser() {
        bindDefaultPathBrowser(forceRemote = true)
    }

    fun openDefaultPathBrowserRoot(forceRemote: Boolean = true) {
        bindDefaultPathBrowser(
            forceRemote = forceRemote,
            preferredPath = "/",
        )
    }

    fun updateDefaultPathBrowserScrollSnapshot(index: Int, offset: Int) {
        defaultPathBrowser.updateScrollSnapshot(
            BrowserScrollSnapshot(index = index, offset = offset)
        )
    }

    private suspend fun listInlineDefaultPathEntries(path: String): ListStorageEntryChildrenResp {
        val normalizedForm = normalizedFormSnapshot()
        if (!normalizedForm.supportsStorageDefaultPath()) {
            return ListStorageEntryChildrenResp.Unknown
        }
        return bridge.run {
            ctListStorageEntryChildrenByArg(
                it,
                normalizedForm,
                normalizeStorageDefaultPath(path),
            )
        } ?: ListStorageEntryChildrenResp.Unknown
    }

    private fun bindDefaultPathBrowser(
        forceRemote: Boolean,
        preferredPath: String? = null,
    ) {
        if (!_defaultPathBrowserExpanded.value) {
            return
        }

        val browserConfig = form.value.defaultPathBrowserConfigOrNull() ?: run {
            unbindDefaultPathBrowser()
            return
        }
        val configChanged = boundDefaultPathBrowserConfig != browserConfig
        if (configChanged) {
            defaultPathBrowser.clearStorageState(StorageId(DEFAULT_PATH_BROWSER_STORAGE_VALUE))
            boundDefaultPathBrowserConfig = browserConfig
        }

        defaultPathBrowser.setStorage(
            BrowserStorageContext(
                storageId = StorageId(DEFAULT_PATH_BROWSER_STORAGE_VALUE),
                isLocal = false,
            )
        )
        val targetPath = when {
            preferredPath != null -> normalizeStorageDefaultPath(preferredPath)
            configChanged -> "/"
            else -> normalizeStorageDefaultPath(defaultPathBrowser.currentPathValue())
        }
        defaultPathBrowser.restorePath(targetPath)
        defaultPathBrowser.refresh(forceRemote = forceRemote || configChanged)
    }

    private fun unbindDefaultPathBrowser() {
        boundDefaultPathBrowserConfig = null
        defaultPathBrowser.setStorage(null)
    }

    private fun setDefaultPathBrowserExpanded(expanded: Boolean) {
        _defaultPathBrowserExpanded.value = expanded
        savedStateHandle[STATE_DEFAULT_PATH_BROWSER_EXPANDED] = expanded
    }

    private fun persistFormDraft(snapshot: ArgUpsertStorage?) {
        savedStateHandle[STATE_FORM_TYPE] = snapshot?.typ?.name
        savedStateHandle[STATE_FORM_ADDR] = snapshot?.addr
        savedStateHandle[STATE_FORM_ALIAS] = snapshot?.alias
        savedStateHandle[STATE_FORM_USERNAME] = snapshot?.username
        savedStateHandle[STATE_FORM_PASSWORD] = snapshot?.password
        savedStateHandle[STATE_FORM_IS_ANONYMOUS] = snapshot?.isAnonymous
        savedStateHandle[STATE_FORM_DEFAULT_PATH] = snapshot?.defaultPath
        savedStateHandle[STATE_FORM_STORAGE_VALUE] = snapshot?.id?.value
    }

    private fun setFormValue(value: ArgUpsertStorage, persist: Boolean = true) {
        _form.value = value
        if (persist) {
            persistFormDraft(value)
        }
    }

    private fun normalizedFormSnapshot(): ArgUpsertStorage {
        val normalized = form.value.copy()
        normalized.defaultPath = normalizeStorageDefaultPath(normalized.defaultPath)
        return normalized
    }

    private fun clearDefaultPathFieldError() {
        _defaultPathFieldError.value = null
    }

    private suspend fun validateDefaultPath(form: ArgUpsertStorage): Boolean {
        val result = bridge.run {
            ctListStorageEntryChildrenByArg(
                it,
                form,
                form.defaultPath,
            )
        } ?: ListStorageEntryChildrenResp.Unknown
        return when (result) {
            is ListStorageEntryChildrenResp.Ok -> {
                clearDefaultPathFieldError()
                true
            }

            ListStorageEntryChildrenResp.AuthenticationFailed -> {
                _defaultPathFieldError.value = R.string.storage_edit_default_path_invalid
                toastRepository.emitToast("认证失败，请检查当前设备配置")
                false
            }

            ListStorageEntryChildrenResp.Timeout -> {
                _defaultPathFieldError.value = R.string.storage_edit_default_path_invalid
                toastRepository.emitToast("目录加载超时，请重试")
                false
            }

            ListStorageEntryChildrenResp.Unknown -> {
                _defaultPathFieldError.value = R.string.storage_edit_default_path_invalid
                toastRepository.emitToastRes(R.string.storage_edit_default_path_invalid)
                false
            }
        }
    }

    private fun emitDefaultPathBrowserLoadFailureToast(state: CurrentStorageStateType) {
        when (state) {
            CurrentStorageStateType.AUTHENTICATION_FAILED -> {
                toastRepository.emitToast("认证失败，请检查当前设备配置")
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
        val previous = form.value
        val next = block(previous.copy())
        if (
            previous.defaultPath != next.defaultPath ||
            previous.defaultPathBrowserConfigOrNull() != next.defaultPathBrowserConfigOrNull()
        ) {
            clearDefaultPathFieldError()
        }
        setFormValue(next)
    }

    fun changeType(typ: StorageType) {
        _formBackups[_form.value.typ] = _form.value.copy()

        val backup = _formBackups[typ]
        if (backup != null) {
            setFormValue(backup)
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
            setFormValue(newForm)
        }
        clearDefaultPathFieldError()
        if (!typ.supportsStorageDefaultPath()) {
            setDefaultPathBrowserExpanded(false)
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
        setFormValue(normalizedForm)
        if (normalizedForm.supportsStorageDefaultPath() && !validateDefaultPath(normalizedForm)) {
            return false
        }
        storageRepository.upsertStorage(normalizedForm)
        persistFormDraft(null)
        return true
    }

    private fun resetTestResult() {
        _testJob?.cancel()
        _testJob = null
        _testResult.value = StorageConnectionTestResult.NONE
    }
}
