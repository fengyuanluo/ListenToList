package com.kutedev.easemusicplayer.widgets.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.ThemeBackgroundImage
import com.kutedev.easemusicplayer.ui.theme.ThemePresets
import com.kutedev.easemusicplayer.ui.theme.buildPaletteColors
import com.kutedev.easemusicplayer.viewmodels.ThemeVM


@Composable
private fun SectionTitle(title: String) {
    Column {
        Text(
            text = title,
            letterSpacing = 1.sp,
            fontSize = 14.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, borderColor, CircleShape)
            .clickable { onClick() }
    )
}

@Composable
fun ThemeSection(
    themeVM: ThemeVM = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by themeVM.settings.collectAsState()
    val paletteColors = remember { buildPaletteColors() }

    val pickBackgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            themeVM.setBackgroundImage(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        SectionTitle(title = stringResource(id = R.string.setting_theme_title))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.setting_theme_preset),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            for (preset in ThemePresets) {
                ColorSwatch(
                    color = preset.color,
                    selected = preset.color.toArgb() == settings.primaryColor.toArgb(),
                    onClick = { themeVM.setPrimaryColor(preset.color) }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.setting_theme_palette),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        val rows = paletteColors.chunked(6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (color in row) {
                        ColorSwatch(
                            color = color,
                            selected = color.toArgb() == settings.primaryColor.toArgb(),
                            onClick = { themeVM.setPrimaryColor(color) }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.setting_theme_background),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            ThemeBackgroundImage(
                uri = settings.backgroundImageUri,
                modifier = Modifier.matchParentSize(),
                alpha = 1f,
            )
            if (settings.backgroundImageUri.isNullOrBlank()) {
                Text(
                    modifier = Modifier.align(Alignment.Center),
                    text = stringResource(id = R.string.setting_theme_background_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EaseTextButton(
                text = stringResource(id = R.string.setting_theme_background_pick),
                type = EaseTextButtonType.PrimaryVariant,
                size = EaseTextButtonSize.Small,
                onClick = {
                    pickBackgroundLauncher.launch(arrayOf("image/*"))
                }
            )
            EaseTextButton(
                text = stringResource(id = R.string.setting_theme_background_clear),
                type = EaseTextButtonType.Default,
                size = EaseTextButtonSize.Small,
                onClick = { themeVM.setBackgroundImage(null) },
                disabled = settings.backgroundImageUri.isNullOrBlank(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
