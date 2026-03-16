package com.kutedev.easemusicplayer.widgets.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseCheckbox
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseSearchField
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.viewmodels.BrowserPathItem
import com.kutedev.easemusicplayer.viewmodels.BrowserScrollSnapshot
import com.kutedev.easemusicplayer.viewmodels.CreatePlaylistVM
import com.kutedev.easemusicplayer.viewmodels.StorageSearchListUiState
import com.kutedev.easemusicplayer.viewmodels.StorageBrowserVM
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import com.kutedev.easemusicplayer.viewmodels.toStorageEntry
import com.kutedev.easemusicplayer.widgets.playlists.CreatePlaylistsDialog
import com.kutedev.easemusicplayer.widgets.search.StorageSearchActionItem
import com.kutedev.easemusicplayer.widgets.search.StorageSearchActionSheet
import com.kutedev.easemusicplayer.widgets.search.StorageSearchErrorCard
import com.kutedev.easemusicplayer.widgets.search.StorageSearchLoadingRow
import com.kutedev.easemusicplayer.widgets.search.StorageSearchResultRow
import com.kutedev.easemusicplayer.widgets.search.StorageSearchScopeSelector
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_backend.StorageEntryType

@Composable
private fun StorageBrowserSkeleton() {
    @Composable
    fun Block(
        width: Dp,
        height: Dp,
    ) {
        val color = MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .background(color, RoundedCornerShape(6.dp))
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .padding(32.dp, 32.dp)
            .testTag("storage_browser_skeleton")
    ) {
        Block(width = 168.dp, height = 20.dp)
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Block(width = 34.dp, height = 34.dp)
                    Block(width = 160.dp, height = 20.dp)
                }
                Block(width = 18.dp, height = 18.dp)
            }
        }
    }
}

@Composable
private fun StorageBrowserError(
    type: CurrentStorageStateType,
    onReload: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val title = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.import_musics_error_authentication_title)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.import_musics_error_timeout_title)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.import_musics_error_unknown_title)
        CurrentStorageStateType.NEED_PERMISSION -> stringResource(id = R.string.import_musics_error_permission_title)
        else -> {
            throw RuntimeException("unsupported type")
        }
    }
    val desc = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.import_musics_error_authentication_desc)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.import_musics_error_timeout_desc)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.import_musics_error_unknown_desc)
        CurrentStorageStateType.NEED_PERMISSION -> stringResource(id = R.string.import_musics_error_permission_desc)
        else -> {
            throw RuntimeException("unsupported type")
        }
    }
    val actionText = if (type == CurrentStorageStateType.NEED_PERMISSION) {
        stringResource(id = R.string.storage_browser_error_request_permission)
    } else {
        stringResource(id = R.string.storage_browser_error_retry)
    }
    val onAction = {
        if (type == CurrentStorageStateType.NEED_PERMISSION) {
            onRequestPermission()
        } else {
            onReload()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Text(
                text = title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = desc,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(0.dp, 240.dp)
            )
            Box(modifier = Modifier.height(12.dp))
            EaseTextButton(
                text = actionText,
                type = EaseTextButtonType.Primary,
                size = EaseTextButtonSize.Medium,
                onClick = onAction
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageBrowserEntry(
    entry: StorageEntry,
    checked: Boolean,
    selectMode: Boolean,
    checkboxTag: String,
    onClickEntry: (entry: StorageEntry) -> Unit,
    onToggle: (entry: StorageEntry) -> Unit,
    onLongClickEntry: (entry: StorageEntry) -> Unit,
) {
    val entryType = entry.entryTyp()
    val hapticFeedback = LocalHapticFeedback.current
    val iconId = when (entryType) {
        StorageEntryType.FOLDER -> R.drawable.icon_folder
        StorageEntryType.IMAGE -> R.drawable.icon_image
        StorageEntryType.MUSIC -> R.drawable.icon_music_note
        else -> R.drawable.icon_file
    }
    val sizeText = if (!entry.isDir) {
        StorageBrowserUtils.formatSize(entry.size?.toLong())
    } else {
        ""
    }
    val allowSelect = entry.isDir || entryType == StorageEntryType.MUSIC
    val iconSize = 28.dp
    val titleSize = 16.sp
    val subTitleSize = 12.sp
    val checkboxSize = 20.dp
    val handleClick = {
        if (selectMode && allowSelect) {
            onToggle(entry)
        } else {
            onClickEntry(entry)
        }
    }

    val rowModifier = if (allowSelect) {
        Modifier.combinedClickable(
            onClick = { handleClick() },
            onLongClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                if (selectMode) {
                    onToggle(entry)
                } else {
                    onLongClickEntry(entry)
                }
            }
        )
    } else {
        Modifier.clickable { handleClick() }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = rowModifier
            .padding(0.dp, 10.dp)
            .fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1.0F)
        ) {
            Icon(
                painter = painterResource(id = iconId),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
            Column {
                Text(
                    text = entry.name,
                    fontSize = titleSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (sizeText.isNotBlank()) {
                    Text(
                        text = sizeText,
                        fontSize = subTitleSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Box(modifier = Modifier.width(14.dp))
        Box(modifier = Modifier.size(if (selectMode) checkboxSize else 0.dp)) {
            if (selectMode && allowSelect) {
                EaseCheckbox(
                    value = checked,
                    onChange = { onToggle(entry) },
                    modifier = Modifier.testTag(checkboxTag),
                    size = checkboxSize,
                )
            }
        }
    }
}

@Composable
private fun StorageBrowserSearchHeader(
    searchState: StorageSearchListUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onScopeChange: (StorageSearchScope) -> Unit,
    onCollapse: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EaseSearchField(
                value = searchState.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(id = R.string.storage_search_placeholder_directory),
                elevated = false,
                onClear = onClearQuery,
                modifier = Modifier.weight(1f),
            )
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = EaseIconButtonType.Default,
                painter = painterResource(id = R.drawable.icon_close),
                onClick = onCollapse,
            )
        }
        StorageSearchScopeSelector(
            selectedScope = searchState.scope,
            onScopeChange = onScopeChange,
        )
        Text(
            text = stringResource(id = R.string.storage_search_current_scope_hint, searchState.parentPath),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StorageBrowserSearchResults(
    searchState: StorageSearchListUiState,
    onClickEntry: (StorageSearchEntry) -> Unit,
    onLongClickEntry: (StorageSearchEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        searchState.loading -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                repeat(3) {
                    StorageSearchLoadingRow()
                }
            }
        }

        searchState.error != null -> {
            StorageSearchErrorCard(
                errorType = searchState.error,
                onRetry = onRetry,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        !searchState.hasResults -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(18.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.storage_search_empty_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(id = R.string.storage_search_empty_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                itemsIndexed(searchState.entries) { _, entry ->
                    StorageSearchResultRow(
                        entry = entry,
                        subtitle = entry.parentPath,
                        onClick = { onClickEntry(entry) },
                        onLongClick = { onLongClickEntry(entry) },
                    )
                }
                if (searchState.loadingMore) {
                    item {
                        StorageSearchLoadingRow(
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (searchState.canLoadMore) {
                    item {
                        EaseTextButton(
                            text = stringResource(id = R.string.storage_search_load_more),
                            type = EaseTextButtonType.PrimaryVariant,
                            size = EaseTextButtonSize.Medium,
                            onClick = onLoadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                item {
                    Box(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun StorageBrowserEntries(
    currentPath: String,
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    selectedPaths: Set<String>,
    selectMode: Boolean,
    isRefreshing: Boolean,
    scrollSnapshot: BrowserScrollSnapshot,
    onNavigateDir: (String) -> Unit,
    onClickEntry: (StorageEntry) -> Unit,
    onToggle: (StorageEntry) -> Unit,
    onLongClickEntry: (StorageEntry) -> Unit,
    onScrollSnapshotChange: (BrowserScrollSnapshot) -> Unit,
) {
    val listState = remember(currentPath) {
        LazyListState(
            firstVisibleItemIndex = scrollSnapshot.index,
            firstVisibleItemScrollOffset = scrollSnapshot.offset,
        )
    }

    LaunchedEffect(currentPath, listState) {
        snapshotFlow {
            BrowserScrollSnapshot(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            )
        }.drop(1).distinctUntilChanged().collect { snapshot ->
            onScrollSnapshotChange(snapshot)
        }
    }

    @Composable
    fun PathTab(
        text: String,
        path: String,
        disabled: Boolean,
        isCurrent: Boolean,
    ) {
        val color = when {
            isCurrent -> MaterialTheme.colorScheme.primary
            !disabled -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val fontSize = if (isCurrent) 14.sp else 13.sp
        val fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(
                    enabled = !disabled,
                    onClick = {
                        onNavigateDir(path)
                    }
                )
                .widthIn(10.dp, 140.dp)
                .padding(6.dp, 4.dp)
        )
    }

    Column {
        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("storage_browser_refreshing")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .wrapContentHeight()
                .padding(32.dp, 10.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            PathTab(
                text = stringResource(id = R.string.import_musics_paths_root),
                path = "/",
                disabled = splitPaths.isEmpty(),
                isCurrent = splitPaths.isEmpty()
            )
            for ((index, v) in splitPaths.withIndex()) {
                Text(
                    text = ">",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PathTab(
                    text = v.name,
                    path = v.path,
                    disabled = index == splitPaths.size - 1,
                    isCurrent = index == splitPaths.size - 1,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(32.dp, 0.dp)
        ) {
            itemsIndexed(entries) { index, entry ->
                StorageBrowserEntry(
                    entry = entry,
                    checked = selectedPaths.contains(entry.path),
                    selectMode = selectMode,
                    checkboxTag = "storage_browser_checkbox_${index}",
                    onClickEntry = { entry ->
                        onClickEntry(entry)
                    },
                    onToggle = { entry ->
                        onToggle(entry)
                    },
                    onLongClickEntry = { entry -> onLongClickEntry(entry) },
                )
            }
            item {
                Box(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun StorageBrowserContent(
    title: String,
    loadState: CurrentStorageStateType,
    searchSupported: Boolean,
    searchExpanded: Boolean,
    searchState: StorageSearchListUiState,
    currentPath: String,
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    selectedPaths: Set<String>,
    selectedCount: Int,
    selectMode: Boolean,
    disableToggleAll: Boolean,
    isRefreshing: Boolean,
    scrollSnapshot: BrowserScrollSnapshot,
    onBack: () -> Unit,
    onNavigateDir: (String) -> Unit,
    onExpandSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onCollapseSearch: () -> Unit,
    onSearchScopeChange: (StorageSearchScope) -> Unit,
    onToggleAll: () -> Unit,
    onClickEntry: (StorageEntry) -> Unit,
    onLongClickEntry: (StorageEntry) -> Unit,
    onClickSearchEntry: (StorageSearchEntry) -> Unit,
    onLongClickSearchEntry: (StorageSearchEntry) -> Unit,
    onLoadMoreSearch: () -> Unit,
    onRetrySearch: () -> Unit,
    onToggleEntry: (StorageEntry) -> Unit,
    onDownloadSelected: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddSelectedToQueue: () -> Unit,
    onRequestPermission: () -> Unit,
    onReload: () -> Unit,
    onScrollSnapshotChange: (BrowserScrollSnapshot) -> Unit,
) {
    val showBlockingLoading = !searchState.active &&
        loadState == CurrentStorageStateType.LOADING &&
        entries.isEmpty()
    val showBlockingError = (
        loadState == CurrentStorageStateType.TIMEOUT ||
            loadState == CurrentStorageStateType.AUTHENTICATION_FAILED ||
            loadState == CurrentStorageStateType.UNKNOWN_ERROR ||
            loadState == CurrentStorageStateType.NEED_PERMISSION
        ) && entries.isEmpty() && !searchState.active

    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxSize()
    ) {
        var selectionActionsExpanded by remember(selectMode, selectedCount) { mutableStateOf(false) }
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(16.dp, 16.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EaseIconButton(
                        sizeType = EaseIconButtonSize.Large,
                        buttonType = EaseIconButtonType.Default,
                        painter = painterResource(id = if (selectMode) R.drawable.icon_close else R.drawable.icon_back),
                        onClick = onBack
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(text = title)
                        if (selectMode && selectedCount > 0) {
                            Text(
                                text = stringResource(
                                    id = R.string.storage_browser_selected,
                                    selectedCount
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("storage_browser_selected")
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searchSupported && !searchExpanded && !selectMode) {
                        EaseIconButton(
                            sizeType = EaseIconButtonSize.Large,
                            buttonType = EaseIconButtonType.Default,
                            painter = painterResource(id = R.drawable.icon_search),
                            onClick = onExpandSearch,
                            modifier = Modifier.testTag("storage_browser_expand_search")
                        )
                    }
                    if (selectMode) {
                        EaseIconButton(
                            sizeType = EaseIconButtonSize.Large,
                            buttonType = EaseIconButtonType.Default,
                            painter = painterResource(id = R.drawable.icon_toggle_all),
                            disabled = disableToggleAll,
                            onClick = onToggleAll,
                            modifier = Modifier.testTag("storage_browser_toggle_all")
                        )
                        Box {
                            EaseIconButton(
                                sizeType = EaseIconButtonSize.Large,
                                buttonType = EaseIconButtonType.Default,
                                painter = painterResource(id = R.drawable.icon_vertialcal_more),
                                disabled = selectedCount == 0,
                                onClick = { selectionActionsExpanded = true },
                                modifier = Modifier.testTag("storage_browser_selection_actions")
                            )
                            DropdownMenu(
                                expanded = selectionActionsExpanded,
                                onDismissRequest = { selectionActionsExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(id = R.string.common_download)) },
                                    onClick = {
                                        selectionActionsExpanded = false
                                        onDownloadSelected()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(id = R.string.common_add_to_playlist)) },
                                    onClick = {
                                        selectionActionsExpanded = false
                                        onAddSelectedToPlaylist()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(id = R.string.common_add_to_queue)) },
                                    onClick = {
                                        selectionActionsExpanded = false
                                        onAddSelectedToQueue()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (searchSupported && searchExpanded) {
                StorageBrowserSearchHeader(
                    searchState = searchState,
                    onQueryChange = onSearchQueryChange,
                    onClearQuery = onClearSearch,
                    onScopeChange = onSearchScopeChange,
                    onCollapse = onCollapseSearch,
                )
            }

            when {
                showBlockingLoading -> StorageBrowserSkeleton()
                showBlockingError -> StorageBrowserError(
                    type = loadState,
                    onReload = onReload,
                    onRequestPermission = onRequestPermission
                )
                searchState.active -> StorageBrowserSearchResults(
                    searchState = searchState,
                    onClickEntry = onClickSearchEntry,
                    onLongClickEntry = onLongClickSearchEntry,
                    onLoadMore = onLoadMoreSearch,
                    onRetry = onRetrySearch,
                )
                else -> StorageBrowserEntries(
                    currentPath = currentPath,
                    splitPaths = splitPaths,
                    entries = entries,
                    selectedPaths = selectedPaths,
                    selectMode = selectMode,
                    isRefreshing = isRefreshing,
                    scrollSnapshot = scrollSnapshot,
                    onNavigateDir = onNavigateDir,
                    onClickEntry = onClickEntry,
                    onToggle = onToggleEntry,
                    onLongClickEntry = onLongClickEntry,
                    onScrollSnapshotChange = onScrollSnapshotChange,
                )
            }
        }
    }
}

@Composable
fun StorageBrowserPage(
    storageBrowserVM: StorageBrowserVM = hiltViewModel(),
    createPlaylistVM: CreatePlaylistVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val loadState by storageBrowserVM.loadState.collectAsState()
    val currentPath by storageBrowserVM.currentPath.collectAsState()
    val currentScrollSnapshot by storageBrowserVM.currentScrollSnapshot.collectAsState()
    val selectedCount by storageBrowserVM.selectedCount.collectAsState()
    val selectMode by storageBrowserVM.selectMode.collectAsState()
    val disableToggleAll by storageBrowserVM.disableToggleAll.collectAsState()
    val isRefreshing by storageBrowserVM.isRefreshing.collectAsState()
    val working by storageBrowserVM.working.collectAsState()
    val storage by storageBrowserVM.storage.collectAsState()
    val searchSupported by storageBrowserVM.searchSupported.collectAsState()
    val searchExpanded by storageBrowserVM.searchExpanded.collectAsState()
    val searchState by storageBrowserVM.searchState.collectAsState()
    val splitPaths by storageBrowserVM.splitPaths.collectAsState()
    val entries by storageBrowserVM.entries.collectAsState()
    val selectedPaths by storageBrowserVM.selected.collectAsState()
    val canNavigateUp by storageBrowserVM.canNavigateUp.collectAsState()
    val scope = rememberCoroutineScope()
    var actionSearchEntry by remember { mutableStateOf<StorageSearchEntry?>(null) }

    val title = storage?.alias?.ifBlank { storage?.addr ?: "Storage" } ?: "Storage"
    fun handleBack() {
        if (selectMode) {
            storageBrowserVM.exitSelectMode()
        } else if (searchState.active || searchExpanded) {
            storageBrowserVM.collapseSearch(clearQuery = true)
        } else if (canNavigateUp) {
            storageBrowserVM.navigateUp()
        } else {
            navController.popBackStack()
        }
    }

    BackHandler(enabled = selectMode || searchState.active || searchExpanded || canNavigateUp) {
        handleBack()
    }

    LaunchedEffect(storageBrowserVM) {
        storageBrowserVM.exitPage.collect {
            navController.popBackStack()
        }
    }

    StorageBrowserContent(
        title = title,
        loadState = loadState,
        searchSupported = searchSupported,
        searchExpanded = searchExpanded,
        searchState = searchState,
        currentPath = currentPath,
        splitPaths = splitPaths,
        entries = entries,
        selectedPaths = selectedPaths,
        selectedCount = selectedCount,
        selectMode = selectMode,
        disableToggleAll = disableToggleAll,
        isRefreshing = isRefreshing,
        scrollSnapshot = currentScrollSnapshot,
        onBack = { handleBack() },
        onNavigateDir = { path -> storageBrowserVM.navigateDir(path) },
        onExpandSearch = { storageBrowserVM.expandSearch() },
        onSearchQueryChange = { value -> storageBrowserVM.updateSearchQuery(value) },
        onClearSearch = { storageBrowserVM.clearSearch() },
        onCollapseSearch = { storageBrowserVM.collapseSearch(clearQuery = true) },
        onSearchScopeChange = { value -> storageBrowserVM.updateSearchScope(value) },
        onToggleAll = { storageBrowserVM.toggleAll() },
        onClickEntry = { entry ->
            storageBrowserVM.clickEntry(entry)
        },
        onLongClickEntry = { entry -> storageBrowserVM.startSelection(entry) },
        onClickSearchEntry = { entry -> storageBrowserVM.clickSearchEntry(entry) },
        onLongClickSearchEntry = { entry -> actionSearchEntry = entry },
        onLoadMoreSearch = { storageBrowserVM.loadMoreSearch() },
        onRetrySearch = { storageBrowserVM.retrySearch() },
        onToggleEntry = { entry -> storageBrowserVM.toggleSelect(entry) },
        onDownloadSelected = {
            scope.launch {
                if (working) {
                    return@launch
                }
                if (storageBrowserVM.enqueueSelectedDownloads()) {
                    storageBrowserVM.exitSelectMode()
                }
            }
        },
        onAddSelectedToPlaylist = {
            scope.launch {
                if (working) {
                    return@launch
                }
                val selectedEntries = storageBrowserVM.collectSelectedMusicEntries()
                if (selectedEntries.isNotEmpty()) {
                    createPlaylistVM.importFromEntries(selectedEntries)
                    storageBrowserVM.exitSelectMode()
                }
            }
        },
        onAddSelectedToQueue = {
            scope.launch {
                if (working) {
                    return@launch
                }
                if (storageBrowserVM.addSelectedToQueue()) {
                    storageBrowserVM.exitSelectMode()
                }
            }
        },
        onRequestPermission = { storageBrowserVM.requestPermission() },
        onReload = { storageBrowserVM.reload() },
        onScrollSnapshotChange = { snapshot ->
            storageBrowserVM.updateCurrentScrollSnapshot(snapshot.index, snapshot.offset)
        }
    )

    val sheetEntry = actionSearchEntry
    if (sheetEntry != null) {
        val actions = buildList {
            add(
                StorageSearchActionItem(
                    label = stringResource(id = R.string.storage_search_action_locate),
                    onClick = {
                        actionSearchEntry = null
                        storageBrowserVM.locateSearchEntry(sheetEntry)
                    }
                )
            )
            if (sheetEntry.entryTyp() == StorageEntryType.MUSIC) {
                add(
                    StorageSearchActionItem(
                        label = stringResource(id = R.string.common_add_to_queue),
                        onClick = {
                            actionSearchEntry = null
                            storageBrowserVM.addSearchEntryToQueue(sheetEntry)
                        }
                    )
                )
                add(
                    StorageSearchActionItem(
                        label = stringResource(id = R.string.common_add_to_playlist),
                        onClick = {
                            actionSearchEntry = null
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
                            actionSearchEntry = null
                            storageBrowserVM.enqueueSearchEntryDownload(sheetEntry)
                        }
                    )
                )
            }
        }
        StorageSearchActionSheet(
            title = sheetEntry.name,
            subtitle = sheetEntry.parentPath,
            items = actions,
            onDismiss = { actionSearchEntry = null },
        )
    }

    CreatePlaylistsDialog()
}
