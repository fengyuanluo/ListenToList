package com.kutedev.easemusicplayer.widgets.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
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
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.viewmodels.BrowserPathItem
import com.kutedev.easemusicplayer.viewmodels.CreatePlaylistVM
import com.kutedev.easemusicplayer.viewmodels.StorageBrowserVM
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import com.kutedev.easemusicplayer.widgets.playlists.CreatePlaylistsDialog
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageEntry
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
        modifier = Modifier.padding(32.dp, 32.dp)
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                    if (type == CurrentStorageStateType.NEED_PERMISSION) {
                        onRequestPermission()
                    } else {
                        onReload()
                    }
                }
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
        }
    }
}

@Composable
private fun StorageBrowserEntry(
    entry: StorageEntry,
    checked: Boolean,
    selectMode: Boolean,
    checkboxTag: String,
    onClickEntry: (entry: StorageEntry) -> Unit,
    onToggle: (entry: StorageEntry) -> Unit
) {
    val entryType = entry.entryTyp()
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .clickable { handleClick() }
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
private fun StorageBrowserEntries(
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    selectedPaths: Set<String>,
    selectMode: Boolean,
    onNavigateDir: (String) -> Unit,
    onClickEntry: (StorageEntry) -> Unit,
    onToggle: (StorageEntry) -> Unit
) {
    @Composable
    fun PathTab(
        text: String,
        path: String,
        disabled: Boolean,
    ) {
        val color = if (!disabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
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
                disabled = splitPaths.isEmpty()
            )
            for ((index, v) in splitPaths.withIndex()) {
                Text(
                    text = ">",
                    fontSize = 12.sp,
                )
                PathTab(
                    text = v.name,
                    path = v.path,
                    disabled = index == splitPaths.size - 1,
                )
            }
        }
        LazyColumn(
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
                    }
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
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    selectedPaths: Set<String>,
    selectedCount: Int,
    selectMode: Boolean,
    disableToggleAll: Boolean,
    onBack: () -> Unit,
    onNavigateDir: (String) -> Unit,
    onToggleAll: () -> Unit,
    onToggleSelectMode: () -> Unit,
    onClickEntry: (StorageEntry) -> Unit,
    onToggleEntry: (StorageEntry) -> Unit,
    onImportSelected: () -> Unit,
    onRequestPermission: () -> Unit,
    onReload: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxSize()
    ) {
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
                        painter = painterResource(id = R.drawable.icon_back),
                        onClick = onBack
                    )
                    Column {
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
                    if (selectMode) {
                        EaseIconButton(
                            sizeType = EaseIconButtonSize.Large,
                            buttonType = EaseIconButtonType.Default,
                            painter = painterResource(id = R.drawable.icon_toggle_all),
                            disabled = disableToggleAll,
                            onClick = onToggleAll,
                            modifier = Modifier.testTag("storage_browser_toggle_all")
                        )
                    }
                    EaseIconButton(
                        sizeType = EaseIconButtonSize.Large,
                        buttonType = if (selectMode) {
                            EaseIconButtonType.Primary
                        } else {
                            EaseIconButtonType.Default
                        },
                        painter = painterResource(
                            id = if (selectMode) {
                                R.drawable.icon_ok
                            } else {
                                R.drawable.icon_mode_list
                            }
                        ),
                        onClick = onToggleSelectMode,
                        modifier = Modifier.testTag("storage_browser_toggle_select_mode")
                    )
                }
            }

            when (loadState) {
                CurrentStorageStateType.LOADING -> StorageBrowserSkeleton()
                CurrentStorageStateType.TIMEOUT,
                CurrentStorageStateType.AUTHENTICATION_FAILED,
                CurrentStorageStateType.UNKNOWN_ERROR,
                CurrentStorageStateType.NEED_PERMISSION -> StorageBrowserError(
                    type = loadState,
                    onReload = onReload,
                    onRequestPermission = onRequestPermission
                )
                else -> StorageBrowserEntries(
                    splitPaths = splitPaths,
                    entries = entries,
                    selectedPaths = selectedPaths,
                    selectMode = selectMode,
                    onNavigateDir = onNavigateDir,
                    onClickEntry = onClickEntry,
                    onToggle = onToggleEntry
                )
            }
        }

        if (selectMode && selectedCount > 0) {
            FloatingActionButton(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.surface,
                onClick = onImportSelected,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset((-48).dp, (-48).dp)
                    .size(64.dp)
                    .testTag("storage_browser_fab")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_download),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
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
    val selectedCount by storageBrowserVM.selectedCount.collectAsState()
    val selectMode by storageBrowserVM.selectMode.collectAsState()
    val disableToggleAll by storageBrowserVM.disableToggleAll.collectAsState()
    val working by storageBrowserVM.working.collectAsState()
    val storage by storageBrowserVM.storage.collectAsState()
    val splitPaths by storageBrowserVM.splitPaths.collectAsState()
    val entries by storageBrowserVM.entries.collectAsState()
    val selectedPaths by storageBrowserVM.selected.collectAsState()
    val canUndo by storageBrowserVM.canUndo.collectAsState()
    val scope = rememberCoroutineScope()

    val title = storage?.alias?.ifBlank { storage?.addr ?: "Storage" } ?: "Storage"
    val playlistPrefix = stringResource(id = R.string.storage_browser_folder_playlist_prefix)
    val rootName = stringResource(id = R.string.import_musics_paths_root)

    fun handleBack() {
        if (selectMode) {
            storageBrowserVM.exitSelectMode()
        } else if (canUndo) {
            storageBrowserVM.undo()
        } else {
            navController.popBackStack()
        }
    }

    BackHandler {
        handleBack()
    }

    StorageBrowserContent(
        title = title,
        loadState = loadState,
        splitPaths = splitPaths,
        entries = entries,
        selectedPaths = selectedPaths,
        selectedCount = selectedCount,
        selectMode = selectMode,
        disableToggleAll = disableToggleAll,
        onBack = { handleBack() },
        onNavigateDir = { path -> storageBrowserVM.navigateDir(path) },
        onToggleAll = { storageBrowserVM.toggleAll() },
        onToggleSelectMode = { storageBrowserVM.toggleSelectMode() },
        onClickEntry = { entry ->
            storageBrowserVM.clickEntry(entry, playlistPrefix, rootName)
        },
        onToggleEntry = { entry -> storageBrowserVM.toggleSelect(entry) },
        onImportSelected = {
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
        onRequestPermission = { storageBrowserVM.requestPermission() },
        onReload = { storageBrowserVM.reload() }
    )

    CreatePlaylistsDialog()
}
