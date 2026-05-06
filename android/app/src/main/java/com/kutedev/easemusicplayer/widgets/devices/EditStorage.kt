package com.kutedev.easemusicplayer.widgets.devices

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.ConfirmDialog
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonColors
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseIndeterminateBar
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.FormSwitch
import com.kutedev.easemusicplayer.components.FormText
import com.kutedev.easemusicplayer.components.FormWidget
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.BrowserPathItem
import com.kutedev.easemusicplayer.viewmodels.BrowserScrollSnapshot
import com.kutedev.easemusicplayer.viewmodels.EditStorageVM
import com.moriafly.salt.ui.ItemOuterEdit
import com.moriafly.salt.ui.ItemOuterTip
import com.moriafly.salt.ui.UnstableSaltUiApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import uniffi.ease_client_backend.CurrentStorageStateType
import uniffi.ease_client_backend.StorageConnectionTestResult
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.ctOnedriveOauthUrl
import uniffi.ease_client_schema.StorageType

private fun buildStr(s: String): AnnotatedString {
    val spans = s.split("$$")

    return buildAnnotatedString {
        for (s in spans) {
            if (s.startsWith("B__")) {
                val s = s.slice("B__".length until s.length)

                withStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight(700)
                    )
                ) {
                    append(s)
                }
            } else {
                append(s)
            }
        }
    }
}

@Composable
private fun RemoveDialog(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val title by editStorageVM.title.collectAsState()
    val musicCount by editStorageVM.musicCount.collectAsState()
    val isOpen by editStorageVM.removeModalOpen.collectAsState()

    val mainDesc = buildStr(
        stringResource(R.string.storage_remove_desc_main)
            .replace("E_TITLE", title)
    )
    val countDesc = buildStr(
        stringResource(R.string.storage_remove_desc_count)
            .replace("E_MCNT", musicCount.toString())
    )

    ConfirmDialog(
        open = isOpen,
        onConfirm = {
            editStorageVM.closeRemoveModal()
            editStorageVM.remove()
            navController.popBackStack()
        },
        onCancel = {
            editStorageVM.closeRemoveModal()
        },
    ) {
        Text(
            text = mainDesc,
            style = EaseTheme.typography.body,
        )
        Text(
            text = countDesc,
            style = EaseTheme.typography.body,
        )
    }
}

@Composable
private fun StorageBlock(
    title: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.primary else EaseTheme.surfaces.secondary
    val tint = if (isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(EaseTheme.radius.hero)
    val borderModifier = if (isActive) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bgColor)
            .then(borderModifier)
            .clickable { onSelect() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.icon_cloud),
                contentDescription = null,
                tint = tint,
            )
            Text(
                text = title,
                color = tint,
            )
        }
        if (isActive) {
            Icon(
                painter = painterResource(id = R.drawable.icon_yes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(16.dp)
            )
        }
    }
}

@Composable
private fun WebdavConfig(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val form by editStorageVM.form.collectAsState()
    val validated by editStorageVM.validated.collectAsState()
    val isAnonymous = form.isAnonymous

    FormSwitch(
        label = stringResource(id = R.string.storage_edit_anonymous),
        value = isAnonymous,
        onChange = {
            editStorageVM.updateForm { storage ->
                storage.isAnonymous = !storage.isAnonymous
                storage
            }
        }
    )
    FormText(
        label = stringResource(id = R.string.storage_edit_alias),
        value = form.alias,
        onChange = { value ->
            editStorageVM.updateForm { storage ->
                storage.alias = value
                storage
            }
        },
    )
    FormText(
        label = stringResource(id = R.string.storage_edit_addr),
        value = form.addr,
        onChange = { value ->
            editStorageVM.updateForm { storage ->
                storage.addr = value
                storage
            }
        },
        error = if (validated.addrEmpty) {
            R.string.storage_edit_form_address
        } else {
            null
        }
    )
    if (!isAnonymous) {
        FormText(
            label = stringResource(id = R.string.storage_edit_username),
            value = form.username,
            onChange = { value ->
                editStorageVM.updateForm { storage ->
                    storage.username = value
                    storage
                }
            },
            error = if (validated.usernameEmpty) {
                R.string.storage_edit_form_username
            } else {
                null
            }
        )
        FormText(
            label = stringResource(id = R.string.storage_edit_password),
            value = form.password,
            isPassword = true,
            onChange = { value ->
                editStorageVM.updateForm { storage ->
                    storage.password = value
                    storage
                }
            },
            error = if (validated.passwordEmpty) {
                R.string.storage_edit_form_password
            } else {
                null
            }
        )
    }

    DefaultPathConfigSection()
}

@Composable
@OptIn(UnstableSaltUiApi::class, ExperimentalFoundationApi::class)
private fun DefaultPathConfigSection(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val form by editStorageVM.form.collectAsState()
    val defaultPathFieldError by editStorageVM.defaultPathFieldError.collectAsState()
    val defaultPathBrowserExpanded by editStorageVM.defaultPathBrowserExpanded.collectAsState()
    val defaultPathBrowserReady by editStorageVM.defaultPathBrowserReady.collectAsState()
    val defaultPathBrowserCurrentPath by editStorageVM.defaultPathBrowserCurrentPath.collectAsState()
    val defaultPathBrowserSplitPaths by editStorageVM.defaultPathBrowserSplitPaths.collectAsState()
    val defaultPathBrowserEntries by editStorageVM.defaultPathBrowserEntries.collectAsState()
    val defaultPathBrowserLoadState by editStorageVM.defaultPathBrowserLoadState.collectAsState()
    val defaultPathBrowserIsRefreshing by editStorageVM.defaultPathBrowserIsRefreshing.collectAsState()
    val defaultPathBrowserScrollSnapshot by editStorageVM.defaultPathBrowserScrollSnapshot.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    FormWidget(label = stringResource(id = R.string.storage_edit_default_path)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
        ) {
            ItemOuterEdit(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            editStorageVM.expandDefaultPathBrowser()
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(250)
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                text = form.defaultPath,
                onChange = { value ->
                    editStorageVM.onDefaultPathInputChange(value)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        editStorageVM.commitDefaultPathInput()
                        keyboardController?.hide()
                    }
                ),
            )
        }
        if (defaultPathFieldError != null) {
            ItemOuterTip(text = stringResource(id = defaultPathFieldError!!))
        }
        if (defaultPathBrowserExpanded) {
            DefaultPathBrowserDropdown(
                modifier = Modifier.padding(top = 6.dp),
                defaultPathBrowserReady = defaultPathBrowserReady,
                defaultPathBrowserLoadState = defaultPathBrowserLoadState,
                defaultPathBrowserEntries = defaultPathBrowserEntries,
                defaultPathBrowserCurrentPath = defaultPathBrowserCurrentPath,
                defaultPathBrowserSplitPaths = defaultPathBrowserSplitPaths,
                defaultPathBrowserIsRefreshing = defaultPathBrowserIsRefreshing,
                defaultPathBrowserScrollSnapshot = defaultPathBrowserScrollSnapshot,
                onReload = { editStorageVM.reloadDefaultPathBrowser() },
                onOpenRoot = { editStorageVM.openDefaultPathBrowserRoot() },
                onNavigateDir = { path -> editStorageVM.navigateDefaultPathBrowserDir(path) },
                onScrollSnapshotChange = { snapshot ->
                    editStorageVM.updateDefaultPathBrowserScrollSnapshot(
                        snapshot.index,
                        snapshot.offset,
                    )
                },
            )
        }
    }
}

@Composable
private fun OpenListConfig(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val form by editStorageVM.form.collectAsState()
    val validated by editStorageVM.validated.collectAsState()
    val isAnonymous = form.isAnonymous

    FormSwitch(
        label = stringResource(id = R.string.storage_edit_anonymous),
        value = isAnonymous,
        onChange = {
            editStorageVM.updateForm { storage ->
                storage.isAnonymous = !storage.isAnonymous
                storage
            }
        }
    )
    FormText(
        label = stringResource(id = R.string.storage_edit_alias),
        value = form.alias,
        onChange = { value ->
            editStorageVM.updateForm { storage ->
                storage.alias = value
                storage
            }
        },
    )
    FormText(
        label = stringResource(id = R.string.storage_edit_addr),
        value = form.addr,
        onChange = { value ->
            editStorageVM.updateForm { storage ->
                storage.addr = value
                storage
            }
        },
        error = if (validated.addrEmpty) {
            R.string.storage_edit_form_address
        } else {
            null
        }
    )
    if (!isAnonymous) {
        FormText(
            label = stringResource(id = R.string.storage_edit_username),
            value = form.username,
            onChange = { value ->
                editStorageVM.updateForm { storage ->
                    storage.username = value
                    storage
                }
            },
            error = if (validated.usernameEmpty) {
                R.string.storage_edit_form_username
            } else {
                null
            }
        )
        FormText(
            label = stringResource(id = R.string.storage_edit_password),
            value = form.password,
            isPassword = true,
            onChange = { value ->
                editStorageVM.updateForm { storage ->
                    storage.password = value
                    storage
                }
            },
            error = if (validated.passwordEmpty) {
                R.string.storage_edit_form_password
            } else {
                null
            }
        )
    }

    DefaultPathConfigSection()
}

@Composable
private fun OneDriveConfig(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val context = LocalContext.current
    val form by editStorageVM.form.collectAsState()
    val validated by editStorageVM.validated.collectAsState()
    val connected = form.password.isNotEmpty()

    FormText(
        label = stringResource(id = R.string.storage_edit_alias),
        value = form.alias,
        onChange = { value ->
            editStorageVM.updateForm { storage ->
                storage.alias = value
                storage
            }
        },
        error = if (validated.aliasEmpty) {
            R.string.storage_edit_onedrive_alias_not_empty
        } else {
            null
        }
    )
    FormWidget(
        label = stringResource(R.string.storage_edit_oauth)
    ) {
        if (!connected) {
            EaseTextButton(
                text = stringResource(R.string.storage_edit_onedrive_connect),
                type = EaseTextButtonType.PrimaryVariant,
                size = EaseTextButtonSize.Medium,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, ctOnedriveOauthUrl().toUri())
                    intent.flags = FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                },
            )
            if (validated.passwordEmpty) {
                Text(
                    modifier = Modifier.padding(
                        horizontal = 0.dp,
                        vertical = 2.dp,
                    ),
                    text = stringResource(R.string.storage_edit_onedrive_should_auth),
                    color = MaterialTheme.colorScheme.error,
                    style = EaseTheme.typography.caption,
                )
            }
        }
        if (connected) {
            EaseTextButton(
                text = stringResource(R.string.storage_edit_onedrive_disconnect),
                type = EaseTextButtonType.Error,
                size = EaseTextButtonSize.Medium,
                onClick = {
                    editStorageVM.updateForm { storage ->
                        storage.password = ""
                        storage
                    }
                },
            )
        }
    }

    DefaultPathConfigSection()
}

@Composable
private fun DefaultPathBrowserError(
    type: CurrentStorageStateType,
    currentPath: String,
    onReload: () -> Unit,
    onOpenRoot: () -> Unit,
) {
    val title = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.import_musics_error_authentication_title)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.import_musics_error_timeout_title)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.import_musics_error_unknown_title)
        else -> stringResource(id = R.string.import_musics_error_unknown_title)
    }
    val desc = when (type) {
        CurrentStorageStateType.AUTHENTICATION_FAILED -> stringResource(id = R.string.storage_edit_default_path_picker_error_auth)
        CurrentStorageStateType.TIMEOUT -> stringResource(id = R.string.storage_edit_default_path_picker_error_timeout)
        CurrentStorageStateType.UNKNOWN_ERROR -> stringResource(id = R.string.storage_edit_default_path_picker_error_unknown)
        else -> stringResource(id = R.string.storage_edit_default_path_picker_error_unknown)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.card))
                .background(EaseTheme.surfaces.secondary)
                .padding(24.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.icon_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = title,
                color = MaterialTheme.colorScheme.error,
                style = EaseTheme.typography.cardTitle,
            )
            Text(
                text = desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.body,
            )
            EaseTextButton(
                text = stringResource(id = R.string.storage_browser_error_retry),
                type = EaseTextButtonType.Primary,
                size = EaseTextButtonSize.Medium,
                onClick = onReload,
            )
            if (currentPath != "/") {
                EaseTextButton(
                    text = stringResource(id = R.string.storage_edit_default_path_picker_back_to_root),
                    type = EaseTextButtonType.Default,
                    size = EaseTextButtonSize.Medium,
                    onClick = onOpenRoot,
                )
            }
        }
    }
}

@Composable
private fun DefaultPathBrowserDropdown(
    modifier: Modifier = Modifier,
    defaultPathBrowserReady: Boolean,
    defaultPathBrowserLoadState: CurrentStorageStateType,
    defaultPathBrowserEntries: List<StorageEntry>,
    defaultPathBrowserCurrentPath: String,
    defaultPathBrowserSplitPaths: List<BrowserPathItem>,
    defaultPathBrowserIsRefreshing: Boolean,
    defaultPathBrowserScrollSnapshot: BrowserScrollSnapshot,
    onReload: () -> Unit,
    onOpenRoot: () -> Unit,
    onNavigateDir: (String) -> Unit,
    onScrollSnapshotChange: (BrowserScrollSnapshot) -> Unit,
) {
    val showBlockingLoading = defaultPathBrowserReady &&
        defaultPathBrowserLoadState == CurrentStorageStateType.LOADING &&
        defaultPathBrowserEntries.isEmpty()
    val showBlockingError = defaultPathBrowserReady && (
        defaultPathBrowserLoadState == CurrentStorageStateType.TIMEOUT ||
            defaultPathBrowserLoadState == CurrentStorageStateType.AUTHENTICATION_FAILED ||
            defaultPathBrowserLoadState == CurrentStorageStateType.UNKNOWN_ERROR
        ) && defaultPathBrowserEntries.isEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(EaseTheme.radius.card))
            .background(EaseTheme.surfaces.secondary)
    ) {
        when {
            !defaultPathBrowserReady -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.storage_edit_default_path_browser_requires_config),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = EaseTheme.typography.body,
                    )
                }
            }

            showBlockingLoading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        EaseIndeterminateBar(modifier = Modifier.fillMaxWidth(0.6f))
                        Text(
                            text = stringResource(id = R.string.storage_edit_default_path_picker_loading),
                            style = EaseTheme.typography.body,
                        )
                    }
                }
            }

            showBlockingError -> {
                DefaultPathBrowserError(
                    type = defaultPathBrowserLoadState,
                    currentPath = defaultPathBrowserCurrentPath,
                    onReload = onReload,
                    onOpenRoot = onOpenRoot,
                )
            }

            else -> {
                DefaultPathBrowserList(
                    currentPath = defaultPathBrowserCurrentPath,
                    splitPaths = defaultPathBrowserSplitPaths,
                    entries = defaultPathBrowserEntries,
                    isRefreshing = defaultPathBrowserIsRefreshing,
                    scrollSnapshot = defaultPathBrowserScrollSnapshot,
                    onNavigateDir = onNavigateDir,
                    onScrollSnapshotChange = onScrollSnapshotChange,
                )
            }
        }
    }
}

@Composable
private fun DefaultPathBrowserList(
    currentPath: String,
    splitPaths: List<BrowserPathItem>,
    entries: List<StorageEntry>,
    isRefreshing: Boolean,
    scrollSnapshot: BrowserScrollSnapshot,
    onNavigateDir: (String) -> Unit,
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
        val fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
        Text(
            text = text,
            color = color,
            style = EaseTheme.typography.bodySmall.copy(fontWeight = fontWeight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable(
                    enabled = !disabled,
                    onClick = {
                        onNavigateDir(path)
                    }
                )
                .widthIn(10.dp, 160.dp)
                .padding(6.dp, 4.dp)
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isRefreshing) {
            EaseIndeterminateBar(modifier = Modifier.fillMaxWidth())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .wrapContentHeight()
                .padding(24.dp, 8.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            PathTab(
                text = stringResource(id = R.string.import_musics_paths_root),
                path = "/",
                disabled = splitPaths.isEmpty(),
                isCurrent = splitPaths.isEmpty()
            )
            for ((index, item) in splitPaths.withIndex()) {
                Text(
                    text = ">",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.bodySmall,
                )
                PathTab(
                    text = item.name,
                    path = item.path,
                    disabled = index == splitPaths.lastIndex,
                    isCurrent = index == splitPaths.lastIndex,
                )
            }
        }
        if (entries.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.storage_edit_default_path_picker_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.body,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                items(entries, key = { entry -> entry.path }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(EaseTheme.radius.card))
                            .clickable {
                                onNavigateDir(entry.path)
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_folder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = entry.name,
                            style = EaseTheme.typography.body,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                item {
                    Box(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun EditStoragesPage(
    editStorageVM: EditStorageVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val coroutineScope = rememberCoroutineScope()
    val form by editStorageVM.form.collectAsState()
    val isCreated by editStorageVM.isCreated.collectAsState()
    val testing by editStorageVM.testResult.collectAsState()

    val storageType = form.typ

    val testingColors = when (testing) {
        StorageConnectionTestResult.NONE -> null
        StorageConnectionTestResult.TESTING -> EaseIconButtonColors(
            buttonBg = Color.Transparent,
            iconTint = MaterialTheme.colorScheme.tertiary,
        )
        StorageConnectionTestResult.SUCCESS -> EaseIconButtonColors(
            buttonBg = Color.Transparent,
            iconTint = MaterialTheme.colorScheme.primary,
        )
        else -> EaseIconButtonColors(
            buttonBg = Color.Transparent,
            iconTint = MaterialTheme.colorScheme.error,
        )
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
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_back),
                    onClick = {
                        navController.popBackStack()
                    }
                )
            }
            Row {
                if (!isCreated) {
                    EaseIconButton(
                        sizeType = EaseIconButtonSize.Medium,
                        buttonType = EaseIconButtonType.Error,
                        painter = painterResource(id = R.drawable.icon_deleteseep),
                        onClick = {
                            editStorageVM.openRemoveModal()
                        }
                    )
                }
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    disabled = testing == StorageConnectionTestResult.TESTING,
                    painter = painterResource(id = R.drawable.icon_wifitethering),
                    overrideColors = testingColors,
                    onClick = {
                        editStorageVM.test()
                    }
                )
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_ok),
                    onClick = {
                        coroutineScope.launch {
                            val finished = editStorageVM.finish()
                            if (finished) {
                                navController.popBackStack()
                            }
                        }
                    }
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(30.dp, 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StorageBlock(
                        title = "WebDAV",
                        isActive = storageType == StorageType.WEBDAV,
                        onSelect = {
                            editStorageVM.changeType(StorageType.WEBDAV)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    StorageBlock(
                        title = "OneDrive",
                        isActive = storageType == StorageType.ONE_DRIVE,
                        onSelect = {
                            editStorageVM.changeType(StorageType.ONE_DRIVE)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    StorageBlock(
                        title = "OpenList",
                        isActive = storageType == StorageType.OPEN_LIST,
                        onSelect = {
                            editStorageVM.changeType(StorageType.OPEN_LIST)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(modifier = Modifier.height(30.dp))
                if (storageType == StorageType.WEBDAV) {
                    WebdavConfig()
                }
                if (storageType == StorageType.ONE_DRIVE) {
                    OneDriveConfig()
                }
                if (storageType == StorageType.OPEN_LIST) {
                    OpenListConfig()
                }
            }
        }
    }
    RemoveDialog()
}
