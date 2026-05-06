package com.kutedev.easemusicplayer.widgets.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseSearchField
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteStorageBrowser
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.CreatePlaylistVM
import com.kutedev.easemusicplayer.viewmodels.StorageSearchErrorType
import com.kutedev.easemusicplayer.viewmodels.StorageSearchSectionUiState
import com.kutedev.easemusicplayer.viewmodels.StorageSearchVM
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import com.kutedev.easemusicplayer.viewmodels.labelRes
import com.kutedev.easemusicplayer.viewmodels.toStorageEntry
import com.kutedev.easemusicplayer.widgets.playlists.CreatePlaylistsDialog
import com.moriafly.salt.ui.ItemOuterLargeTitle
import com.moriafly.salt.ui.UnstableSaltUiApi
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_schema.StorageId

private const val SEARCH_AUTO_LOAD_MORE_BUFFER = 3

private val SearchResultListStateSaver = Saver<LazyListState, List<Int>>(
    save = { state -> listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
    restore = { payload ->
        LazyListState(
            firstVisibleItemIndex = payload.getOrElse(0) { 0 },
            firstVisibleItemScrollOffset = payload.getOrElse(1) { 0 },
        )
    },
)

private fun storageSearchDisplayName(storage: Storage): String {
    val alias = storage.alias.trim()
    if (alias.isNotBlank()) {
        return alias
    }
    return runCatching {
        android.net.Uri.parse(storage.addr).host
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: storage.addr
}

@Composable
private fun SearchTopBar(
    query: String,
    scope: StorageSearchScope,
    canFilter: Boolean,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onScopeChange: (StorageSearchScope) -> Unit,
) {
    var filterExpanded by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_back),
            onClick = onBack,
        )
        EaseSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(id = R.string.storage_search_placeholder_home),
            elevated = false,
            onSearch = {},
            onClear = onClearQuery,
            modifier = Modifier.weight(1f),
        )
        Box {
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = if (scope == StorageSearchScope.ALL) {
                    EaseIconButtonType.Default
                } else {
                    EaseIconButtonType.Primary
                },
                painter = painterResource(id = R.drawable.icon_adjust),
                onClick = { filterExpanded = true },
                disabled = !canFilter,
            )
            DropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { filterExpanded = false },
            ) {
                StorageSearchScope.values().forEach { targetScope ->
                    DropdownMenuItem(
                        text = {
                            Text(text = stringResource(id = targetScope.labelRes()))
                        },
                        onClick = {
                            filterExpanded = false
                            onScopeChange(targetScope)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceTabs(
    storages: List<Storage>,
    selectedStorageId: StorageId?,
    onSelectStorage: (StorageId) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedStorageId, storages) {
        val selectedIndex = storages.indexOfFirst { it.id == selectedStorageId }
        if (selectedIndex > 1) {
            scrollState.animateScrollTo((selectedIndex * 92).coerceAtLeast(0))
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        storages.forEach { storage ->
            val selected = storage.id == selectedStorageId
            val shape = RoundedCornerShape(EaseTheme.radius.control)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else EaseTheme.surfaces.chip
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
                        },
                        shape = shape,
                    )
                    .clickable { onSelectStorage(storage.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = storageSearchDisplayName(storage),
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    style = EaseTheme.typography.label.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(UnstableSaltUiApi::class)
@Composable
private fun SearchPageMessage(
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EaseTheme.radius.card))
            .background(EaseTheme.surfaces.secondary)
            .padding(EaseTheme.spacing.sm)
    ) {
        ItemOuterLargeTitle(
            text = title,
            sub = desc,
        )
    }
}

@Composable
private fun SearchPageError(
    errorType: StorageSearchErrorType,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StorageSearchErrorCard(
        errorType = errorType,
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun InstanceSearchResultsPage(
    storage: Storage,
    query: String,
    scope: StorageSearchScope,
    section: StorageSearchSectionUiState?,
    onResultClick: (StorageSearchEntry) -> Unit,
    onResultLongClick: (StorageSearchEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageState = section ?: StorageSearchSectionUiState(storage = storage)
    val listState = rememberSaveable(
        storage.id.value,
        query,
        scope.name,
        saver = SearchResultListStateSaver,
    ) {
        LazyListState()
    }
    var lastAutoLoadPage by remember(storage.id.value, query, scope.name) {
        mutableIntStateOf(0)
    }
    val shouldAutoLoad by remember(pageState.canLoadMore, pageState.entries, pageState.loadingMore, listState) {
        derivedStateOf {
            if (!pageState.canLoadMore || pageState.entries.isEmpty() || pageState.loadingMore) {
                return@derivedStateOf false
            }
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            val triggerIndex = (pageState.entries.lastIndex - SEARCH_AUTO_LOAD_MORE_BUFFER).coerceAtLeast(0)
            lastVisibleIndex >= triggerIndex
        }
    }

    LaunchedEffect(storage.id.value, query, scope.name, pageState.page, shouldAutoLoad) {
        if (!shouldAutoLoad || pageState.page <= lastAutoLoadPage) {
            return@LaunchedEffect
        }
        lastAutoLoadPage = pageState.page
        onLoadMore()
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            query.isBlank() -> {
                item {
                    SearchPageMessage(
                        title = stringResource(id = R.string.storage_search_idle_title),
                        desc = stringResource(id = R.string.storage_search_idle_desc),
                    )
                }
            }

            pageState.loading && !pageState.hasResults -> {
                items(4) {
                    StorageSearchLoadingRow()
                }
            }

            pageState.error != null && !pageState.hasResults -> {
                item {
                    SearchPageError(
                        errorType = pageState.error,
                        onRetry = onRetry,
                    )
                }
            }

            !pageState.hasResults -> {
                item {
                    SearchPageMessage(
                        title = stringResource(id = R.string.storage_search_section_empty_title),
                        desc = stringResource(id = R.string.storage_search_section_empty_desc),
                    )
                }
            }

            else -> {
                items(
                    items = pageState.entries,
                    key = { entry -> "${entry.storageId.value}:${entry.path}:${entry.isDir}" },
                ) { entry ->
                    StorageSearchResultRow(
                        entry = entry,
                        subtitle = entry.parentPath,
                        onClick = { onResultClick(entry) },
                        onLongClick = { onResultLongClick(entry) },
                    )
                }
                if (pageState.loadingMore) {
                    item {
                        StorageSearchLoadingRow()
                    }
                }
                if (pageState.error != null) {
                    item {
                        SearchPageError(
                            errorType = pageState.error,
                            onRetry = onRetry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StorageSearchContent(
    query: String,
    scope: StorageSearchScope,
    selectedStorageId: StorageId?,
    searchableStorages: List<Storage>,
    sections: List<StorageSearchSectionUiState>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (StorageSearchScope) -> Unit,
    onClearQuery: () -> Unit,
    onSelectStorage: (StorageId) -> Unit,
    onResultClick: (StorageSearchEntry) -> Unit,
    onResultLongClick: (StorageSearchEntry) -> Unit,
    onLoadMore: (StorageId) -> Unit,
    onRetryStorage: (StorageId) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val sectionsById = remember(sections) {
        sections.associateBy { section -> section.storage.id.value }
    }
    val pagerState = rememberPagerState(pageCount = { searchableStorages.size.coerceAtLeast(1) })
    val selectedIndex = searchableStorages.indexOfFirst { it.id == selectedStorageId }.let { index ->
        if (index >= 0) index else 0
    }

    LaunchedEffect(selectedIndex, searchableStorages.size) {
        if (searchableStorages.isEmpty()) {
            return@LaunchedEffect
        }
        if (pagerState.currentPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }
    LaunchedEffect(pagerState, searchableStorages) {
        if (searchableStorages.isEmpty()) {
            return@LaunchedEffect
        }
        snapshotFlow { pagerState.currentPage }.collect { page ->
            searchableStorages.getOrNull(page)?.let { storage ->
                onSelectStorage(storage.id)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EaseTheme.surfaces.screen)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchTopBar(
                query = query,
                scope = scope,
                canFilter = searchableStorages.isNotEmpty(),
                onBack = onBack,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                onScopeChange = onScopeChange,
            )
            if (searchableStorages.isEmpty()) {
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    SearchPageMessage(
                        title = stringResource(id = R.string.storage_search_no_instance_title),
                        desc = stringResource(id = R.string.storage_search_no_instance_desc),
                    )
                }
            } else {
                InstanceTabs(
                    storages = searchableStorages,
                    selectedStorageId = selectedStorageId,
                    onSelectStorage = { storageId ->
                        onSelectStorage(storageId)
                        val targetIndex = searchableStorages.indexOfFirst { it.id == storageId }
                        if (targetIndex >= 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(targetIndex)
                            }
                        }
                    },
                )
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = searchableStorages.size > 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val storage = searchableStorages[page]
                    val section = sectionsById[storage.id.value]
                    InstanceSearchResultsPage(
                        storage = storage,
                        query = query,
                        scope = scope,
                        section = section,
                        onResultClick = onResultClick,
                        onResultLongClick = onResultLongClick,
                        onLoadMore = { onLoadMore(storage.id) },
                        onRetry = { onRetryStorage(storage.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun StorageSearchPage(
    storageSearchVM: StorageSearchVM = hiltViewModel(),
    createPlaylistVM: CreatePlaylistVM = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val query by storageSearchVM.query.collectAsState()
    val scope by storageSearchVM.scope.collectAsState()
    val sections by storageSearchVM.sections.collectAsState()
    val searchableStorages by storageSearchVM.searchableStorages.collectAsState()
    val selectedStorageId by storageSearchVM.selectedStorageId.collectAsState()
    var actionEntry by remember { mutableStateOf<StorageSearchEntry?>(null) }

    fun openEntry(entry: StorageSearchEntry) {
        when {
            entry.isDir -> navController.navigate(
                RouteStorageBrowser(entry.storageId.value.toString(), entry.path)
            )

            entry.entryTyp() == StorageEntryType.MUSIC -> {
                storageSearchVM.playEntry(entry)
            }

            else -> navController.navigate(
                RouteStorageBrowser(entry.storageId.value.toString(), entry.parentPath)
            )
        }
    }

    BackHandler(onBack = { navController.popBackStack() })

    StorageSearchContent(
        query = query,
        scope = scope,
        selectedStorageId = selectedStorageId,
        searchableStorages = searchableStorages,
        sections = sections,
        onBack = { navController.popBackStack() },
        onQueryChange = { value -> storageSearchVM.updateQuery(value) },
        onScopeChange = { value -> storageSearchVM.updateScope(value) },
        onClearQuery = { storageSearchVM.clearQuery() },
        onSelectStorage = { storageId -> storageSearchVM.selectStorage(storageId) },
        onResultClick = { entry -> openEntry(entry) },
        onResultLongClick = { entry -> actionEntry = entry },
        onLoadMore = { storageId -> storageSearchVM.loadMore(storageId) },
        onRetryStorage = { storageId -> storageSearchVM.retryStorage(storageId) },
    )

    val sheetEntry = actionEntry
    if (sheetEntry != null) {
        val actions = buildList {
            add(
                StorageSearchActionItem(
                    label = stringResource(id = R.string.storage_search_action_locate),
                    onClick = {
                        actionEntry = null
                        navController.navigate(
                            RouteStorageBrowser(
                                sheetEntry.storageId.value.toString(),
                                if (sheetEntry.isDir) sheetEntry.path else sheetEntry.parentPath,
                            )
                        )
                    }
                )
            )
            if (sheetEntry.entryTyp() == StorageEntryType.MUSIC) {
                add(
                    StorageSearchActionItem(
                        label = stringResource(id = R.string.common_add_to_queue),
                        onClick = {
                            actionEntry = null
                            storageSearchVM.addEntryToQueue(sheetEntry)
                        }
                    )
                )
                add(
                    StorageSearchActionItem(
                        label = stringResource(id = R.string.common_add_to_playlist),
                        onClick = {
                            actionEntry = null
                            createPlaylistVM.importFromEntries(listOf(sheetEntry.toStorageEntry()))
                        }
                    )
                )
            }
            if (!sheetEntry.isDir) {
                add(
                    StorageSearchActionItem(
                        label = stringResource(id = R.string.common_download),
                        onClick = {
                            actionEntry = null
                            storageSearchVM.downloadEntry(sheetEntry)
                        }
                    )
                )
            }
        }
        StorageSearchActionSheet(
            title = sheetEntry.name,
            subtitle = sheetEntry.parentPath,
            items = actions,
            onDismiss = { actionEntry = null },
        )
    }

    CreatePlaylistsDialog()
}
