package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.ItemOuterTip

private val paddingX = SettingPaddingX

@Composable
fun ThemeSettingsPage() {
    SettingsSubpageScaffold(
        title = stringResource(id = R.string.setting_theme_entry_title),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = paddingX)
        ) {
            ItemOuterTip(text = stringResource(id = R.string.setting_theme_entry_desc))
            Box(modifier = Modifier.height(EaseTheme.spacing.md))
            ThemeSection(showTitle = false)
            Box(modifier = Modifier.height(EaseTheme.spacing.xl))
        }
    }
}
