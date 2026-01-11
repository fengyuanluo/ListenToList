package com.kutedev.easemusicplayer.widgets.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kutedev.easemusicplayer.R

private val paddingX = SettingPaddingX

@Composable
fun ThemeSettingsPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            modifier = Modifier.padding(start = paddingX, end = paddingX, top = 24.dp, bottom = 4.dp),
            text = stringResource(id = R.string.setting_theme_entry_title),
            fontSize = 32.sp,
        )
        Text(
            modifier = Modifier.padding(horizontal = paddingX),
            text = stringResource(id = R.string.setting_theme_entry_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        Box(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.padding(horizontal = paddingX)) {
            ThemeSection(showTitle = false)
        }
        Box(modifier = Modifier.height(24.dp))
    }
}
