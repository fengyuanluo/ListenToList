package com.kutedev.easemusicplayer.widgets.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseCheckbox
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.singleton.DownloadTaskItem
import com.kutedev.easemusicplayer.singleton.DownloadTaskStatus
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import com.kutedev.easemusicplayer.viewmodels.DownloadManagerVM

private val paddingX = SettingPaddingX
private val taskShape = RoundedCornerShape(EaseTheme.radius.compact)

@Composable
private fun DownloadStatusChip(
    task: DownloadTaskItem,
) {
    val (label, bg, fg) = when (task.status) {
        DownloadTaskStatus.QUEUED -> Triple(
            stringResource(id = R.string.download_status_queued),
            EaseTheme.surfaces.chip,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadTaskStatus.RUNNING -> Triple(
            stringResource(id = R.string.download_status_running),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.primary,
        )
        DownloadTaskStatus.PAUSED -> Triple(
            stringResource(id = R.string.download_status_paused),
            EaseTheme.surfaces.chip,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadTaskStatus.COMPLETED -> Triple(
            stringResource(id = R.string.download_status_completed),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.secondary,
        )
        DownloadTaskStatus.FAILED -> Triple(
            stringResource(id = R.string.download_status_failed),
            MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.error,
        )
        DownloadTaskStatus.CANCELLED -> Triple(
            stringResource(id = R.string.download_status_cancelled),
            EaseTheme.surfaces.chip,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadTaskStatus.BLOCKED -> Triple(
            stringResource(id = R.string.download_status_blocked),
            EaseTheme.surfaces.chip,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(EaseTheme.radius.control))
            .padding(horizontal = EaseTheme.spacing.sm, vertical = EaseTheme.spacing.xxs + 1.dp)
    ) {
        Text(
            text = label,
            color = fg,
            style = EaseTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

private fun downloadProgressLabel(task: DownloadTaskItem): String? {
    val totalBytes = task.totalBytes
    val downloadedBytes = task.bytesDownloaded
    if (downloadedBytes <= 0L && totalBytes == null) {
        return null
    }
    val downloadedText = StorageBrowserUtils.formatSize(downloadedBytes)
    if (totalBytes == null || totalBytes <= 0L) {
        return downloadedText
    }
    return "${downloadedText} / ${StorageBrowserUtils.formatSize(totalBytes)}"
}

private data class DownloadTaskSupportText(
    val text: String,
    val color: androidx.compose.ui.graphics.Color,
)

@Composable
private fun downloadTaskSupportText(task: DownloadTaskItem): DownloadTaskSupportText? {
    val fallbackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorMessage = task.errorMessage?.takeIf { it.isNotBlank() }
    return when {
        task.status == DownloadTaskStatus.FAILED && errorMessage != null -> DownloadTaskSupportText(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
        )

        else -> downloadProgressLabel(task)?.let { progress ->
            DownloadTaskSupportText(
                text = progress,
                color = fallbackColor,
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit,
) {
    val supportText = downloadTaskSupportText(task)

    Column(
        verticalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .background(EaseTheme.surfaces.secondary, taskShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                shape = taskShape,
            )
            .padding(horizontal = EaseTheme.spacing.sm, vertical = EaseTheme.spacing.sm)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = task.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (task.pausable) {
                EaseTextButton(
                    text = stringResource(id = R.string.download_action_pause),
                    type = EaseTextButtonType.Default,
                    size = EaseTextButtonSize.Small,
                    onClick = onPause,
                )
            }
            if (task.startable) {
                EaseTextButton(
                    text = stringResource(id = R.string.download_action_start),
                    type = EaseTextButtonType.Primary,
                    size = EaseTextButtonSize.Small,
                    onClick = onStart,
                )
            }
            EaseTextButton(
                text = stringResource(id = R.string.download_action_delete),
                type = EaseTextButtonType.Error,
                size = EaseTextButtonSize.Small,
                onClick = onDelete,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DownloadStatusChip(task = task)
            supportText?.let { detail ->
                Text(
                    text = detail.text,
                    color = detail.color,
                    style = EaseTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DownloadDirectoryDialog(
    open: Boolean,
    summary: String,
    canReset: Boolean,
    onPickDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) {
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.card))
                .background(EaseTheme.surfaces.dialog)
                .padding(EaseTheme.spacing.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(id = R.string.download_settings_title),
                style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = stringResource(id = R.string.setting_downloads_dir, summary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs)) {
                EaseTextButton(
                    text = stringResource(id = R.string.download_settings_change_directory),
                    type = EaseTextButtonType.PrimaryVariant,
                    size = EaseTextButtonSize.Small,
                    onClick = onPickDirectory,
                )
                EaseTextButton(
                    text = stringResource(id = R.string.download_settings_reset_directory),
                    type = EaseTextButtonType.Default,
                    size = EaseTextButtonSize.Small,
                    onClick = onResetDirectory,
                    disabled = !canReset,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                EaseTextButton(
                    text = stringResource(id = R.string.confirm_dialog_btn_cancel),
                    type = EaseTextButtonType.Primary,
                    size = EaseTextButtonSize.Small,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun DeleteDownloadDialog(
    task: DownloadTaskItem?,
    deleteFile: Boolean,
    onDeleteFileChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (task == null) {
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.card))
                .background(EaseTheme.surfaces.dialog)
                .padding(EaseTheme.spacing.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        ) {
            Text(
                text = stringResource(id = R.string.download_delete_title),
                color = MaterialTheme.colorScheme.error,
                style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = task.title,
                style = EaseTheme.typography.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
            ) {
                EaseCheckbox(
                    value = deleteFile,
                    onChange = onDeleteFileChange,
                    size = 18.dp,
                )
                Text(
                    text = stringResource(id = R.string.download_delete_remove_file),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs)) {
                    EaseTextButton(
                        text = stringResource(id = R.string.confirm_dialog_btn_cancel),
                        type = EaseTextButtonType.Default,
                        size = EaseTextButtonSize.Small,
                        onClick = onDismiss,
                    )
                    EaseTextButton(
                        text = stringResource(id = R.string.download_action_delete),
                        type = EaseTextButtonType.Error,
                        size = EaseTextButtonSize.Small,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadManagerPage(
    downloadManagerVM: DownloadManagerVM = hiltViewModel(),
) {
    val context = LocalContext.current
    val tasks by downloadManagerVM.tasks.collectAsState(initial = emptyList())
    val directoryState by downloadManagerVM.downloadDirectoryState.collectAsState()
    var showDirectoryDialog by remember { mutableStateOf(false) }
    var pendingDeleteTask by remember { mutableStateOf<DownloadTaskItem?>(null) }
    var deleteFileWithTask by remember { mutableStateOf(false) }

    val pickDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            downloadManagerVM.setDownloadDirectory(uri.toString())
        }
        showDirectoryDialog = false
    }

    SettingsSubpageScaffold(
        title = stringResource(id = R.string.setting_downloads_title),
        trailing = {
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = EaseIconButtonType.Default,
                painter = painterResource(id = R.drawable.icon_adjust),
                onClick = { showDirectoryDialog = true },
            )
        },
    ) { contentModifier ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = paddingX,
                end = paddingX,
                top = EaseTheme.spacing.xs,
                bottom = EaseTheme.spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
            modifier = contentModifier,
        ) {
            item {
                Text(
                    text = stringResource(id = R.string.setting_downloads_dir, directoryState.summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (tasks.isEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                EaseTheme.surfaces.secondary,
                                taskShape,
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                                shape = taskShape,
                            )
                            .padding(horizontal = EaseTheme.spacing.sm, vertical = EaseTheme.spacing.sm)
                    ) {
                        Text(
                            text = stringResource(id = R.string.download_empty_title),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = EaseTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                }
            } else {
                items(tasks, key = { task -> task.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = { downloadManagerVM.pause(task.id) },
                        onStart = { downloadManagerVM.start(task.id) },
                        onDelete = {
                            pendingDeleteTask = task
                            deleteFileWithTask = false
                        },
                    )
                }
            }
        }
    }

    DownloadDirectoryDialog(
        open = showDirectoryDialog,
        summary = directoryState.summary,
        canReset = directoryState.treeUri != null,
        onPickDirectory = { pickDirectoryLauncher.launch(null) },
        onResetDirectory = {
            downloadManagerVM.resetDownloadDirectory()
            showDirectoryDialog = false
        },
        onDismiss = { showDirectoryDialog = false },
    )

    DeleteDownloadDialog(
        task = pendingDeleteTask,
        deleteFile = deleteFileWithTask,
        onDeleteFileChange = { deleteFileWithTask = it },
        onConfirm = {
            pendingDeleteTask?.let { task ->
                downloadManagerVM.delete(task.id, deleteFileWithTask)
            }
            pendingDeleteTask = null
            deleteFileWithTask = false
        },
        onDismiss = {
            pendingDeleteTask = null
            deleteFileWithTask = false
        },
    )
}
