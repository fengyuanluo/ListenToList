package com.kutedev.easemusicplayer.widgets.musics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseCheckbox
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.BrowserPathItem
import com.kutedev.easemusicplayer.viewmodels.BrowserScrollSnapshot
import com.kutedev.easemusicplayer.viewmodels.ImportVM
import com.kutedev.easemusicplayer.viewmodels.StoragesVM
import com.kutedev.easemusicplayer.viewmodels.VImportStorageEntry
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType

@Composable
private fun ImportEntriesSkeleton() {
    @Composable
    fun Block(
        width: Dp,
        height: Dp,
    ) {
        val color = MaterialTheme.colorScheme.surfaceVariant
        Box(modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(EaseTheme.radius.sm))
            .background(color)
        )
    }

    @Composable
    fun FolderItem() {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(30.dp)
        ) {
            Block(width = 30.dp, height = 30.dp)
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight()
            ) {
                Block(width = 138.dp, height = 17.dp)
                Block(width = 45.dp, height = 9.dp)
            }
        }
    }

    @Composable
    fun FileItem() {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Block(width = 30.dp, height = 30.dp)
                Block(width = 138.dp, height = 17.dp)
            }
            Block(width = 16.dp, height = 16.dp)
        }
    }


    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(28.dp, 28.dp)
    ) {
        Block(
            width = 144.dp,
            height = 17.dp
        )
        FolderItem()
        FileItem()
        FileItem()
    }
}

@Composable
private fun ImportEntry(
    entry: StorageEntry,
    checked: Boolean,
    allowTypes: List<StorageEntryType>,
    onClickEntry: (entry: StorageEntry) -> Unit
) {
    val entryTyp = entry.entryTyp()
    val canCheck = allowTypes.any({t -> t == entryTyp })
    val painter = when (entryTyp) {
        StorageEntryType.FOLDER -> painterResource(id = R.drawable.icon_folder)
        StorageEntryType.IMAGE -> painterResource(id = R.drawable.icon_image)
        StorageEntryType.MUSIC -> painterResource(id = R.drawable.icon_music_note)
        else -> painterResource(id = R.drawable.icon_file)
    }
    val onClick = {
        onClickEntry(entry);
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(0.dp, 8.dp)
            .fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1.0F)
                .clickable { onClick() }
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
            )
        Text(
            text = entry.name,
            style = EaseTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        }
        Box(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(16.dp)
        ) {
            if (canCheck) {
                EaseCheckbox(
                    value = checked,
                    onChange = {
                        onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportSelectionBadge(
    selectedCount: Int,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(EaseTheme.radius.control)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(EaseTheme.surfaces.secondary)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                shape = shape,
            )
            .padding(
                horizontal = EaseTheme.spacing.md,
                vertical = EaseTheme.spacing.xs,
            )
    ) {
        Text(
            text = selectedCount.toString(),
            color = MaterialTheme.colorScheme.primary,
            style = EaseTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ImportEntries(
    currentPath: String,
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    selected: Set<String>,
    selectedCount: Int,
    allowTypes: List<StorageEntryType>,
    isRefreshing: Boolean,
    scrollSnapshot: BrowserScrollSnapshot,
    onNavigateDir: (String) -> Unit,
    onClickEntry: (StorageEntry) -> Unit,
    onFinish: () -> Unit,
    onScrollSnapshotChange: (BrowserScrollSnapshot) -> Unit,
) {
    val navController = LocalNavController.current
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
        val fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        Text(
            text = text,
            color = color,
            style = if (isCurrent) {
                EaseTheme.typography.bodySmall.copy(fontWeight = fontWeight)
            } else {
                EaseTheme.typography.bodySmall.copy(fontWeight = fontWeight)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(
                    enabled = !disabled,
                    onClick = {
                        onNavigateDir(path)
                    }
                )
                .clip(RoundedCornerShape(EaseTheme.radius.xs))
                .widthIn(10.dp, 100.dp)
                .padding(4.dp, 2.dp)
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column {
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .wrapContentHeight()
                    .padding(28.dp, 8.dp)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                    ,
                    style = EaseTheme.typography.bodySmall,
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
                    .padding(28.dp, 0.dp)
            ) {
                items(entries) {
                    ImportEntry(
                        entry = it,
                        checked = selected.contains(it.path),
                        allowTypes = allowTypes,
                        onClickEntry = onClickEntry,
                    )
                }
                item {
                    Box(modifier = Modifier.height(12.dp))
                }
            }
        }
        if (selectedCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = EaseTheme.spacing.xl + EaseTheme.spacing.sm,
                        bottom = EaseTheme.spacing.xl + EaseTheme.spacing.sm,
                    )
            ) {
                ImportSelectionBadge(selectedCount = selectedCount)
                EaseTextButton(
                    text = stringResource(id = R.string.confirm_dialog_btn_ok),
                    type = EaseTextButtonType.Primary,
                    size = EaseTextButtonSize.Medium,
                    onClick = {
                        navController.popBackStack()
                        onFinish()
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportStorages(
    storagesVM: StoragesVM = hiltViewModel(),
    importVM: ImportVM = hiltViewModel()
) {
    val storageItems by storagesVM.storages.collectAsState()
    val selectedStorageId by importVM.selectedStorageId.collectAsState()

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(28.dp, 0.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        for (_item in storageItems) {
            val item = VImportStorageEntry(_item)

            val selected = selectedStorageId == item.id

            val textColor = if (selected) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(EaseTheme.radius.sm))
                    .clickable {
                        importVM.selectStorage(item.id)
                    }
                    .background(if (selected) MaterialTheme.colorScheme.primary else EaseTheme.surfaces.secondary)
                    .width(142.dp)
                    .height(65.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp, 16.dp)
                ) {
                    Text(
                        text = item.name,
                        color = textColor,
                        style = EaseTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        color = textColor,
                        style = EaseTheme.typography.micro,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!item.isLocal) {
                    Icon(
                        painter = painterResource(id = R.drawable.icon_cloud),
                        contentDescription = null,
                        tint = Color.Black.copy(0.2F),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .width(27.dp)
                            .offset(7.dp, 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportMusicsWarningImpl(
    title: String,
    subTitle: String,
    color: Color,
    iconPainter: Painter,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.xs))
                .clickable {
                    onClick()
                }
                .padding(EaseTheme.spacing.sm)
        ) {
            Box(modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(color)
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.Center)
                )
            }
            Text(
                text = title,
                color = color,
                style = EaseTheme.typography.body,
            )
            Text(
                text = subTitle,
                modifier = Modifier
                    .widthIn(0.dp, 220.dp)
                ,
                style = EaseTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ImportNoStorageState() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.xs))
                .padding(EaseTheme.spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(EaseTheme.surfaces.secondary)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Text(
                text = stringResource(id = R.string.import_musics_empty_storage_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.body,
            )
            Text(
                text = stringResource(id = R.string.import_musics_empty_storage_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(0.dp, 220.dp),
                style = EaseTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ImportMusicsError(
    type: CurrentStorageStateType,
    onRequestPermission: () -> Unit,
    onReload: () -> Unit,
) {
    val title = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.import_musics_error_authentication_title)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.import_musics_error_timeout_title)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.import_musics_error_unknown_title)
        CurrentStorageStateType.NEED_PERMISSION -> stringResource(id = R.string.import_musics_error_permission_title)
        CurrentStorageStateType.LOADING,
        CurrentStorageStateType.OK -> error("unsupported type")
    }
    val desc = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.import_musics_error_authentication_desc)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.import_musics_error_timeout_desc)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.import_musics_error_unknown_desc)
        CurrentStorageStateType.NEED_PERMISSION -> stringResource(id = R.string.import_musics_error_permission_desc)
        CurrentStorageStateType.LOADING,
        CurrentStorageStateType.OK -> error("unsupported type")
    }

    ImportMusicsWarningImpl(
        title = title,
        subTitle = desc,
        color = MaterialTheme.colorScheme.error,
        iconPainter = painterResource(id = R.drawable.icon_warning),
        onClick = {
            if (type == CurrentStorageStateType.NEED_PERMISSION) {
                onRequestPermission()
            } else {
                onReload()
            }
        }
    )
}

@Composable
fun ImportMusicsPage(
    importVM: ImportVM = hiltViewModel(),
    storagesVM: StoragesVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val currentPath by importVM.currentPath.collectAsState()
    val currentScrollSnapshot by importVM.currentScrollSnapshot.collectAsState()
    val entries by importVM.entries.collectAsState()
    val selected by importVM.selected.collectAsState()
    val allowTypes by importVM.allowTypes.collectAsState()
    val selectedCount by importVM.selectedCount.collectAsState()
    val splitPaths by importVM.splitPaths.collectAsState()
    val canNavigateUp by importVM.canNavigateUp.collectAsState()
    val disabledToggleAll by importVM.disabledToggleAll.collectAsState()
    val hasAvailableStorage by importVM.hasAvailableStorage.collectAsState()
    val isRefreshing by importVM.isRefreshing.collectAsState()
    val loadState by importVM.loadState.collectAsState()
    val showBlockingLoading = loadState == CurrentStorageStateType.LOADING && entries.isEmpty()
    val showBlockingError = (
        loadState == CurrentStorageStateType.TIMEOUT ||
            loadState == CurrentStorageStateType.AUTHENTICATION_FAILED ||
            loadState == CurrentStorageStateType.UNKNOWN_ERROR ||
            loadState == CurrentStorageStateType.NEED_PERMISSION
        ) && entries.isEmpty()

    val titleText = when (selectedCount) {
        0 -> stringResource(id = R.string.import_musics_title_default)
        1 -> "${selectedCount} ${stringResource(id = R.string.import_musics_title_single_suffix)}"
        else -> "${selectedCount} ${stringResource(id = R.string.import_musics_title_multi_suffix)}"
    }
    fun navigateBack() {
        if (canNavigateUp) {
            importVM.navigateUp()
        } else {
            navController.popBackStack()
        }
    }

    BackHandler(enabled = canNavigateUp) {
        navigateBack()
    }
    Column(
        modifier = Modifier
            .background(EaseTheme.surfaces.screen)
            .fillMaxSize()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(13.dp, 13.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_back),
                    onClick = {
                        navigateBack()
                    }
                )
                Text(
                    text = titleText,
                    style = EaseTheme.typography.cardTitle,
                )
            }
            Row {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_toggle_all),
                    disabled = disabledToggleAll,
                    onClick = {
                        importVM.toggleAll()
                    }
                )
            }
        }
        ImportStorages(storagesVM = storagesVM, importVM = importVM)
        when {
            !hasAvailableStorage -> ImportNoStorageState()
            showBlockingLoading -> ImportEntriesSkeleton()
            showBlockingError -> ImportMusicsError(
                type = loadState,
                onRequestPermission = { importVM.requestPermission() },
                onReload = { importVM.reload() },
            )
            else -> {
                ImportEntries(
                    currentPath = currentPath,
                    splitPaths = splitPaths,
                    entries = entries,
                    selected = selected,
                    selectedCount = selectedCount,
                    allowTypes = allowTypes,
                    isRefreshing = isRefreshing,
                    scrollSnapshot = currentScrollSnapshot,
                    onNavigateDir = { path -> importVM.navigateDir(path) },
                    onClickEntry = { entry -> importVM.clickEntry(entry) },
                    onFinish = { importVM.finish() },
                    onScrollSnapshotChange = { snapshot ->
                        importVM.updateCurrentScrollSnapshot(snapshot.index, snapshot.offset)
                    },
                )
            }
        }
    }
}
