package com.kutedev.easemusicplayer.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.kutedev.easemusicplayer.R
import com.moriafly.salt.ui.dialog.YesNoDialog

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

    YesNoDialog(
        onDismissRequest = onCancel,
        onConfirm = onConfirm,
        properties = DialogProperties(),
        title = stringResource(id = R.string.confirm_dialog_title),
        content = "",
        drawContent = content,
        cancelText = stringResource(id = R.string.confirm_dialog_btn_cancel),
        confirmText = stringResource(id = R.string.confirm_dialog_btn_ok),
    )
}
