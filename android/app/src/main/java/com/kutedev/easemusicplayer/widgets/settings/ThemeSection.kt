package com.kutedev.easemusicplayer.widgets.settings

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
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
private fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.Center)
        ) {
            drawRoundRect(
                brush = Brush.horizontalGradient(gradientColors),
                cornerRadius = CornerRadius(12f, 12f)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                thumbColor = thumbColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ThemeSection(
    showTitle: Boolean = true,
    themeVM: ThemeVM = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by themeVM.settings.collectAsState()

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

    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0.8f) }
    var value by remember { mutableStateOf(0.9f) }

    LaunchedEffect(settings.primaryColor) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(settings.primaryColor.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    val previewColor = Color.hsv(hue, saturation, value)
    val hueGradient = remember {
        listOf(
            Color.hsv(0f, 1f, 1f),
            Color.hsv(60f, 1f, 1f),
            Color.hsv(120f, 1f, 1f),
            Color.hsv(180f, 1f, 1f),
            Color.hsv(240f, 1f, 1f),
            Color.hsv(300f, 1f, 1f),
            Color.hsv(360f, 1f, 1f),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        if (showTitle) {
            SectionTitle(title = stringResource(id = R.string.setting_theme_title))
            Spacer(modifier = Modifier.height(12.dp))
        }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(previewColor)
                    .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(10.dp))
            )
            val hex = remember(previewColor) {
                String.format("#%06X", 0xFFFFFF and previewColor.toArgb())
            }
            Text(
                text = "${stringResource(id = R.string.setting_theme_preview)} $hex",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.setting_theme_hue),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradientSlider(
            value = hue,
            onValueChange = { nextHue ->
                hue = nextHue
                themeVM.setPrimaryColor(Color.hsv(hue, saturation, value))
            },
            valueRange = 0f..360f,
            gradientColors = hueGradient,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = stringResource(id = R.string.setting_theme_saturation),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradientSlider(
            value = saturation,
            onValueChange = { nextSaturation ->
                saturation = nextSaturation
                themeVM.setPrimaryColor(Color.hsv(hue, saturation, value))
            },
            valueRange = 0f..1f,
            gradientColors = listOf(
                Color.hsv(hue, 0f, value),
                Color.hsv(hue, 1f, value),
            ),
            modifier = Modifier.padding(vertical = 4.dp),
            thumbColor = previewColor,
        )
        Text(
            text = stringResource(id = R.string.setting_theme_brightness),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradientSlider(
            value = value,
            onValueChange = { nextValue ->
                value = nextValue
                themeVM.setPrimaryColor(Color.hsv(hue, saturation, value))
            },
            valueRange = 0f..1f,
            gradientColors = listOf(
                Color.Black,
                Color.hsv(hue, saturation, 1f),
            ),
            modifier = Modifier.padding(vertical = 4.dp),
            thumbColor = previewColor,
        )
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
