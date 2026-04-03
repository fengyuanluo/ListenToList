package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.ui.theme.EaseTheme

@Composable
fun ConfirmDialog(
    open: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!open) {
        return
    }

    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.card))
                .background(EaseTheme.surfaces.dialog)
                .padding(EaseTheme.spacing.dialogPadding),
        ) {
            Text(
                text = stringResource(id = R.string.confirm_dialog_title),
                color = MaterialTheme.colorScheme.error,
                style = EaseTheme.typography.cardTitle,
            )
            Box(modifier = Modifier.height(EaseTheme.spacing.xxs))
            content()
            Row(
                horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.xs, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                EaseTextButton(
                    text = stringResource(id = R.string.confirm_dialog_btn_cancel),
                    type = EaseTextButtonType.Default,
                    size = EaseTextButtonSize.Medium,
                    onClick = onCancel
                )
                EaseTextButton(
                    text = stringResource(id = R.string.confirm_dialog_btn_ok),
                    type = EaseTextButtonType.Primary,
                    size = EaseTextButtonSize.Medium,
                    onClick = onConfirm
                )
            }
        }
    }
}
