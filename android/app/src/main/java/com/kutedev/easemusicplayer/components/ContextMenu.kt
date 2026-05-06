package com.kutedev.easemusicplayer.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.UnstableSaltUiApi
import com.moriafly.salt.ui.popup.PopupMenu
import com.moriafly.salt.ui.popup.PopupMenuItem

data class EaseContextMenuItem(
    val stringId: Int,
    val onClick: () -> Unit,
    val isError: Boolean = false,
)

@OptIn(UnstableSaltUiApi::class)
@Composable
fun EaseContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<EaseContextMenuItem>,
) {
    PopupMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        items.forEach { item ->
            PopupMenuItem(
                onClick = {
                    onDismissRequest()
                    item.onClick()
                },
                text = stringResource(id = item.stringId),
                iconColor = if (item.isError) {
                    SaltTheme.colors.highlight
                } else {
                    Color.Unspecified
                },
            )
        }
    }
}
