package com.kutedev.easemusicplayer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils

private fun adjustLightness(color: Color, delta: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun buildColorScheme(primary: Color, darkTheme: Boolean): androidx.compose.material3.ColorScheme {
    val secondary = adjustLightness(primary, if (darkTheme) 0.15f else 0.25f)
    val surfaceVariant = if (darkTheme) Color(0xFF303030) else Color(0xFFE3E3E3)
    return if (darkTheme) {
        darkColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = Pink80,
            surfaceVariant = surfaceVariant
        )
    } else {
        lightColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = Pink40,
            surfaceVariant = surfaceVariant
        )
    }
}

@Composable
fun EaseMusicPlayerTheme(
    themeSettings: ThemeSettings,
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        //        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        //            val context = LocalContext.current
        //            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        //        }
        else -> buildColorScheme(themeSettings.primaryColor, darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
