package com.kutedev.easemusicplayer.widgets.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.dropShadow
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.StorageSearchErrorType
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import com.kutedev.easemusicplayer.viewmodels.labelRes
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.StorageSearchEntry as BackendStorageSearchEntry
import androidx.compose.foundation.ExperimentalFoundationApi

data class StorageSearchActionItem(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
fun StorageSearchScopeSelector(
    selectedScope: StorageSearchScope,
    onScopeChange: (StorageSearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.horizontalScroll(rememberScrollState())
    ) {
        StorageSearchScope.values().forEach { scope ->
            val selected = scope == selectedScope
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                        },
                        shape = shape,
                    )
                    .clickable { onScopeChange(scope) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = stringResource(id = scope.labelRes()),
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    style = EaseTheme.typography.label.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
fun StorageSearchErrorCard(
    errorType: StorageSearchErrorType,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(EaseTheme.radius.card)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(
                color = EaseTheme.surfaces.shadow,
                offsetX = 0.dp,
                offsetY = 4.dp,
                blurRadius = 14.dp,
            )
            .clip(shape)
            .background(EaseTheme.surfaces.card)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
            .padding(EaseTheme.spacing.cardPadding)
    ) {
        Text(
            text = stringResource(id = errorType.titleRes),
            color = MaterialTheme.colorScheme.error,
            style = EaseTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(id = errorType.bodyRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = EaseTheme.typography.bodySmall,
        )
        if (onRetry != null) {
            EaseTextButton(
                text = stringResource(id = R.string.storage_search_retry),
                type = EaseTextButtonType.Primary,
                size = EaseTextButtonSize.Medium,
                onClick = onRetry,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StorageSearchResultRow(
    entry: BackendStorageSearchEntry,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryType = entry.entryTyp()
    val hapticFeedback = LocalHapticFeedback.current
    val iconId = when (entryType) {
        StorageEntryType.FOLDER -> R.drawable.icon_folder
        StorageEntryType.MUSIC -> R.drawable.icon_music_note
        StorageEntryType.IMAGE -> R.drawable.icon_image
        else -> R.drawable.icon_file
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(EaseTheme.radius.card))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(EaseTheme.radius.compact))
                .background(EaseTheme.surfaces.secondary)
        ) {
            Icon(
                painter = painterResource(id = iconId),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = entry.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSearchActionSheet(
    title: String,
    subtitle: String,
    items: List<StorageSearchActionItem>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EaseTheme.surfaces.dialog,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.sectionTitle.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            items.forEach { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(EaseTheme.radius.card))
                        .clickable { item.onClick() }
                        .padding(horizontal = EaseTheme.spacing.md, vertical = EaseTheme.spacing.sm)
                ) {
                    Text(
                        text = item.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = EaseTheme.typography.cardTitle.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
            Box(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun StorageSearchLoadingRow(
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(EaseTheme.radius.card)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(EaseTheme.surfaces.secondary)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(EaseTheme.surfaces.card)
            )
            Box(
                modifier = Modifier
                    .width(190.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(EaseTheme.surfaces.card)
            )
        }
    }
}
