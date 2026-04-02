package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.FormSwitch
import com.kutedev.easemusicplayer.components.FormText
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.singleton.LrcApiSettings
import com.kutedev.easemusicplayer.viewmodels.LrcApiSettingsVM

private val paddingX = SettingPaddingX

@Composable
fun LrcApiSettingsPage(
    viewModel: LrcApiSettingsVM = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var draftEnabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var draftBaseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var draftAuthKey by rememberSaveable(settings.authKey) { mutableStateOf(settings.authKey) }
    val draftSettings = LrcApiSettings(
        enabled = draftEnabled,
        baseUrl = draftBaseUrl,
        authKey = draftAuthKey,
    )
    val hasUnsavedChanges = draftSettings != settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EaseTheme.surfaces.screen)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            modifier = Modifier.padding(start = paddingX, end = paddingX, top = 24.dp, bottom = 4.dp),
            text = stringResource(id = R.string.setting_lrcapi_entry_title),
            style = EaseTheme.typography.screenTitle,
        )
        Text(
            modifier = Modifier.padding(horizontal = paddingX),
            text = stringResource(id = R.string.setting_lrcapi_page_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = EaseTheme.typography.body,
        )
        Box(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.padding(horizontal = paddingX)) {
            FormSwitch(
                label = stringResource(id = R.string.setting_lrcapi_enabled),
                value = draftEnabled,
                onChange = { draftEnabled = it },
            )
            Box(modifier = Modifier.height(12.dp))
            FormText(
                label = stringResource(id = R.string.setting_lrcapi_base_url),
                value = draftBaseUrl,
                onChange = { draftBaseUrl = it },
            )
            Text(
                text = stringResource(id = R.string.setting_lrcapi_base_url_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.micro,
                modifier = Modifier.padding(top = 4.dp),
            )
            Box(modifier = Modifier.height(12.dp))
            FormText(
                label = stringResource(id = R.string.setting_lrcapi_auth_key),
                value = draftAuthKey,
                onChange = { draftAuthKey = it },
                isPassword = true,
            )
            if (hasUnsavedChanges) {
                Text(
                    text = stringResource(id = R.string.setting_lrcapi_unsaved_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.micro,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Box(modifier = Modifier.height(16.dp))
            EaseTextButton(
                text = stringResource(id = R.string.setting_lrcapi_apply),
                type = EaseTextButtonType.Primary,
                size = EaseTextButtonSize.Medium,
                disabled = !hasUnsavedChanges,
                onClick = {
                    viewModel.save(draftSettings)
                },
            )
            Box(modifier = Modifier.height(8.dp))
            EaseTextButton(
                text = stringResource(id = R.string.setting_lrcapi_reset),
                type = EaseTextButtonType.Default,
                size = EaseTextButtonSize.Medium,
                onClick = {
                    draftEnabled = false
                    draftBaseUrl = ""
                    draftAuthKey = ""
                    viewModel.reset()
                },
            )
        }
        Box(modifier = Modifier.height(24.dp))
    }
}
