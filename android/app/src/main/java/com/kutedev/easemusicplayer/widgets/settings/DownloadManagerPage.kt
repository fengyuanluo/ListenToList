package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.singleton.DownloadTaskItem
import com.kutedev.easemusicplayer.singleton.DownloadTaskStatus
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.utils.StorageBrowserUtils
import com.kutedev.easemusicplayer.viewmodels.DownloadManagerVM

private val paddingX = SettingPaddingX

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(EaseTheme.surfaces.card, RoundedCornerShape(EaseTheme.radius.card))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                shape = RoundedCornerShape(EaseTheme.radius.card),
            )
            .padding(EaseTheme.spacing.cardPadding)
    ) {
        Text(
            text = task.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadStatusChip(task = task)
            downloadProgressLabel(task)?.let { progressLabel ->
                Box(
                    modifier = Modifier
                        .background(
                            EaseTheme.surfaces.chip,
                            RoundedCornerShape(EaseTheme.radius.control),
                        )
                        .padding(horizontal = EaseTheme.spacing.sm, vertical = EaseTheme.spacing.xxs + 1.dp)
                ) {
                    Text(
                        text = progressLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = EaseTheme.typography.caption,
                    )
                }
            }
        }
        Text(
            text = task.sourcePath,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = EaseTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = task.destinationPath,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = EaseTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        task.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = EaseTheme.typography.bodySmall,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (task.active) {
                EaseTextButton(
                    text = stringResource(id = R.string.download_action_cancel),
                    type = EaseTextButtonType.Error,
                    size = EaseTextButtonSize.Small,
                    onClick = onCancel,
                )
            }
            if (task.retryable) {
                EaseTextButton(
                    text = stringResource(id = R.string.download_action_retry),
                    type = EaseTextButtonType.Primary,
                    size = EaseTextButtonSize.Small,
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
fun DownloadManagerPage(
    downloadManagerVM: DownloadManagerVM = hiltViewModel(),
) {
    val tasks by downloadManagerVM.tasks.collectAsState(initial = emptyList())
    val downloadDirectory = downloadManagerVM.downloadDirectory()

    LazyColumn(
        contentPadding = PaddingValues(start = paddingX, end = paddingX, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(EaseTheme.surfaces.screen)
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(id = R.string.setting_downloads_title),
                    style = EaseTheme.typography.screenTitle,
                )
                Text(
                    text = stringResource(id = R.string.setting_downloads_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.body,
                )
                Text(
                    text = stringResource(id = R.string.setting_downloads_dir, downloadDirectory),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.bodySmall,
                )
            }
        }

        if (tasks.isEmpty()) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            EaseTheme.surfaces.secondary,
                            RoundedCornerShape(EaseTheme.radius.card),
                        )
                        .padding(EaseTheme.spacing.lg)
                ) {
                    Text(
                        text = stringResource(id = R.string.download_empty_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = stringResource(id = R.string.download_empty_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = EaseTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            items(tasks, key = { task -> task.id }) { task ->
                DownloadTaskCard(
                    task = task,
                    onCancel = { downloadManagerVM.cancel(task.id) },
                    onRetry = { downloadManagerVM.retry(task) },
                )
            }
        }

        item {
            Box(modifier = Modifier.height(12.dp))
        }
    }
}
