package com.kutedev.easemusicplayer.viewmodels

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.singleton.StorageSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.SearchStorageEntriesResp
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_schema.StorageId

private const val ARG_QUERY = "query"
private const val STATE_QUERY = "storage_search_query"
private const val STATE_SCOPE = "storage_search_scope"
private const val STATE_SELECTED_STORAGE_ID = "storage_search_selected_storage_id"

@HiltViewModel
@OptIn(FlowPreview::class)
class StorageSearchVM @Inject constructor(
    private val storageRepository: StorageRepository,
    private val storageSearchRepository: StorageSearchRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    private val downloadRepository: DownloadRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val initialQuery = savedStateHandle.get<String>(STATE_QUERY)
        ?: Uri.decode(savedStateHandle.get<String>(ARG_QUERY) ?: "")
    private val initialScope = savedStateHandle.get<String>(STATE_SCOPE)
        ?.let { raw -> runCatching { StorageSearchScope.valueOf(raw) }.getOrNull() }
        ?: StorageSearchScope.ALL
    private val initialSelectedStorageId = savedStateHandle.get<Long>(STATE_SELECTED_STORAGE_ID)
        ?.let(::StorageId)
    private val _query = MutableStateFlow(initialQuery)
    private val _scope = MutableStateFlow(initialScope)
    private val _sections = MutableStateFlow<List<StorageSearchSectionUiState>>(emptyList())
    private val _selectedStorageId = MutableStateFlow(initialSelectedStorageId)
    private var refreshSeq: Long = 0L

    val query = _query.asStateFlow()
    val scope = _scope.asStateFlow()
    val sections = _sections.asStateFlow()
    val selectedStorageId = _selectedStorageId.asStateFlow()
    val searchableStorages = storageRepository.storages
        .map { storages -> storages.filter { storage -> storage.isStorageSearchSupported() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val totalHits = sections
        .map { current -> current.sumOf { it.total } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        viewModelScope.launch {
            storageRepository.reload()
        }
        viewModelScope.launch {
            searchableStorages.collectLatest { storages ->
                val current = _selectedStorageId.value
                val next = when {
                    storages.isEmpty() -> null
                    current == null || storages.none { item -> item.id == current } -> storages.first().id
                    else -> current
                }
                if (next != current) {
                    _selectedStorageId.value = next
                    savedStateHandle[STATE_SELECTED_STORAGE_ID] = next?.value
                }
            }
        }
        viewModelScope.launch {
            combine(
                searchableStorages,
                _query.debounce(320),
                _scope,
            ) { storages, query, scope ->
                Triple(storages, query.trim(), scope)
            }.collectLatest { (storages, query, scope) ->
                persistSearchState(query = _query.value, scope = scope)
                if (storages.isEmpty()) {
                    _sections.value = emptyList()
                    return@collectLatest
                }
                if (query.isBlank()) {
                    _sections.value = storages.map { storage -> StorageSearchSectionUiState(storage = storage) }
                    return@collectLatest
                }
                refreshAllSections(storages = storages, query = query, scope = scope)
            }
        }
    }

    fun selectStorage(storageId: StorageId) {
        if (_selectedStorageId.value == storageId) {
            return
        }
        if (searchableStorages.value.none { item -> item.id == storageId }) {
            return
        }
        _selectedStorageId.value = storageId
        savedStateHandle[STATE_SELECTED_STORAGE_ID] = storageId.value
    }

    fun updateQuery(value: String) {
        _query.value = value
        persistSearchState(query = value, scope = _scope.value)
    }

    fun clearQuery() {
        updateQuery("")
    }

    fun updateScope(value: StorageSearchScope) {
        if (_scope.value == value) {
            return
        }
        _scope.value = value
        persistSearchState(query = _query.value, scope = value)
    }

    fun retryStorage(storageId: StorageId) {
        val storage = searchableStorages.value.firstOrNull { item -> item.id == storageId } ?: return
        val query = _query.value.trim()
        if (query.isBlank()) {
            return
        }
        val token = refreshSeq
        viewModelScope.launch {
            loadSectionPage(
                storage = storage,
                query = query,
                scope = _scope.value,
                page = 1,
                token = token,
                append = false,
            )
        }
    }

    fun loadMore(storageId: StorageId) {
        val section = _sections.value.firstOrNull { item -> item.storage.id == storageId } ?: return
        if (!section.canLoadMore) {
            return
        }
        val query = _query.value.trim()
        if (query.isBlank()) {
            return
        }
        val token = refreshSeq
        viewModelScope.launch {
            loadSectionPage(
                storage = section.storage,
                query = query,
                scope = _scope.value,
                page = section.page + 1,
                token = token,
                append = true,
            )
        }
    }

    fun playEntry(entry: StorageSearchEntry) {
        viewModelScope.launch {
            storageSearchRepository.playSearchEntry(entry)
        }
    }

    fun addEntryToQueue(entry: StorageSearchEntry) {
        if (entry.entryTyp() != uniffi.ease_client_backend.StorageEntryType.MUSIC) {
            return
        }
        playerControllerRepository.appendEntriesToQueue(listOf(entry.toStorageEntry()))
    }

    fun downloadEntry(entry: StorageSearchEntry) {
        if (entry.isDir) {
            return
        }
        downloadRepository.enqueueEntries(listOf(entry.toStorageEntry()))
    }

    private suspend fun refreshAllSections(
        storages: List<Storage>,
        query: String,
        scope: StorageSearchScope,
    ) {
        val token = ++refreshSeq
        _sections.value = storages.map { storage ->
            StorageSearchSectionUiState(storage = storage, loading = true)
        }
        val results = coroutineScope {
            storages.map { storage ->
                async {
                    storage.id to storageSearchRepository.search(
                        storageId = storage.id,
                        parent = "/",
                        keywords = query,
                        scope = scope,
                        page = 1,
                        perPage = STORAGE_AGGREGATE_SEARCH_PAGE_SIZE,
                    )
                }
            }.awaitAll().toMap()
        }
        if (token != refreshSeq) {
            return
        }
        _sections.value = storages.map { storage ->
            buildSectionState(
                storage = storage,
                response = results[storage.id] ?: SearchStorageEntriesResp.Unknown,
                page = 1,
                previous = null,
            )
        }
    }

    private suspend fun loadSectionPage(
        storage: Storage,
        query: String,
        scope: StorageSearchScope,
        page: Int,
        token: Long,
        append: Boolean,
    ) {
        val previous = _sections.value.firstOrNull { item -> item.storage.id == storage.id }
            ?: StorageSearchSectionUiState(storage = storage)
        _sections.value = _sections.value.map { section ->
            if (section.storage.id != storage.id) {
                section
            } else if (append) {
                section.copy(loadingMore = true, error = null)
            } else {
                section.copy(loading = true, loadingMore = false, error = null, entries = emptyList(), total = 0)
            }
        }
        val response = storageSearchRepository.search(
            storageId = storage.id,
            parent = "/",
            keywords = query,
            scope = scope,
            page = page,
            perPage = STORAGE_AGGREGATE_SEARCH_PAGE_SIZE,
        )
        if (token != refreshSeq || query != _query.value.trim() || scope != _scope.value) {
            return
        }
        _sections.value = _sections.value.map { section ->
            if (section.storage.id != storage.id) {
                section
            } else {
                buildSectionState(
                    storage = storage,
                    response = response,
                    page = page,
                    previous = if (append) previous else null,
                )
            }
        }
    }

    private fun buildSectionState(
        storage: Storage,
        response: SearchStorageEntriesResp,
        page: Int,
        previous: StorageSearchSectionUiState?,
    ): StorageSearchSectionUiState {
        val resultPage = response.pageOrNull()
        if (resultPage != null) {
            val entries = if (previous == null || page <= 1) {
                resultPage.entries
            } else {
                mergeSearchPages(previous.entries, resultPage.entries)
            }
            return StorageSearchSectionUiState(
                storage = storage,
                entries = entries,
                total = resultPage.total.toInt(),
                loading = false,
                loadingMore = false,
                error = null,
                page = page,
            )
        }
        return StorageSearchSectionUiState(
            storage = storage,
            entries = previous?.entries ?: emptyList(),
            total = previous?.total ?: 0,
            loading = false,
            loadingMore = false,
            error = response.errorTypeOrNull(),
            page = previous?.page ?: 0,
        )
    }

    private fun persistSearchState(query: String, scope: StorageSearchScope) {
        savedStateHandle[STATE_QUERY] = query
        savedStateHandle[STATE_SCOPE] = scope.name
    }
}
