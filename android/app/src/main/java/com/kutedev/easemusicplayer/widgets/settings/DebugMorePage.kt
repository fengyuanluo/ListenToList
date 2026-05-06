package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.ConfirmDialog
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.DebugMoreVM

private val paddingX = SettingPaddingX


@Composable
private fun Item(
    title: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.height(56.dp))
        Text(
            modifier = Modifier.padding(horizontal = paddingX),
            text = title,
            style = EaseTheme.typography.body,
        )
    }
}

@Composable
fun DebugMorePage(
    debugMoreVM: DebugMoreVM = hiltViewModel()
) {
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTitle by remember { mutableStateOf("") }

    fun requestConfirm(title: String, action: () -> Unit) {
        pendingTitle = title
        pendingAction = action
    }
    val rustErrorTitle = stringResource(id = R.string.debug_trigger_rs_err)
    val rustAsyncErrorTitle = stringResource(id = R.string.debug_trigger_rs_async_err)
    val rustPanicTitle = stringResource(id = R.string.debug_trigger_rs_panic)
    val kotlinErrorTitle = stringResource(id = R.string.debug_trigger_kt_exception)
    val kotlinAsyncErrorTitle = stringResource(id = R.string.debug_trigger_kt_async_exception)

    SettingsSubpageScaffold(
        title = stringResource(id = R.string.setting_debug),
    ) { contentModifier ->
        Column(modifier = contentModifier) {
            Box(modifier = Modifier.height(EaseTheme.spacing.xs))
            Item(
                title = rustErrorTitle,
                onClick = {
                    requestConfirm(rustErrorTitle) {
                        debugMoreVM.triggerRustError()
                    }
                }
            )
            Item(
                title = rustAsyncErrorTitle,
                onClick = {
                    requestConfirm(rustAsyncErrorTitle) {
                        debugMoreVM.triggerRustAsyncError()
                    }
                }
            )
            Item(
                title = rustPanicTitle,
                onClick = {
                    requestConfirm(rustPanicTitle) {
                        debugMoreVM.triggerRustPanic()
                    }
                }
            )
            Item(
                title = kotlinErrorTitle,
                onClick = {
                    requestConfirm(kotlinErrorTitle) {
                        debugMoreVM.triggerKotlinError()
                    }
                }
            )
            Item(
                title = kotlinAsyncErrorTitle,
                onClick = {
                    requestConfirm(kotlinAsyncErrorTitle) {
                        debugMoreVM.triggerKotlinAsyncError()
                    }
                }
            )
        }
    }

    ConfirmDialog(
        open = pendingAction != null,
        onConfirm = {
            pendingAction?.invoke()
            pendingAction = null
        },
        onCancel = {
            pendingAction = null
        }
    ) {
        Text(
            text = stringResource(id = R.string.debug_confirm_desc, pendingTitle),
            style = EaseTheme.typography.body,
        )
    }
}
