package com.kutedev.easemusicplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

private fun adjustLightness(color: Color, delta: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun blend(base: Color, overlay: Color, amount: Float): Color {
    return Color(ColorUtils.blendARGB(base.toArgb(), overlay.toArgb(), amount.coerceIn(0f, 1f)))
}

private fun buildColorScheme(primary: Color, darkTheme: Boolean): ColorScheme {
    val secondary = adjustLightness(primary, if (darkTheme) 0.15f else 0.25f)
    val tertiary = adjustLightness(primary, if (darkTheme) 0.24f else 0.34f)
    val surfaceVariant = if (darkTheme) Color(0xFF30343A) else Color(0xFFE5E7EC)
    return if (darkTheme) {
        darkColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            surfaceVariant = surfaceVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            surfaceVariant = surfaceVariant,
        )
    }
}

@Immutable
data class EaseSurfaces(
    val screen: Color,
    val secondary: Color,
    val card: Color,
    val dialog: Color,
    val chip: Color,
    val shadow: Color,
    val backdropImageAlpha: Float,
    val backdropScrim: Color,
    val coverScrim: Color,
    val hasBackdrop: Boolean,
)

private fun buildSurfaceTokens(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    hasBackdrop: Boolean,
): EaseSurfaces {
    val screenBase = blend(colorScheme.surface, colorScheme.surfaceVariant, if (darkTheme) 0.22f else 0.10f)
    val secondaryBase = blend(colorScheme.surface, colorScheme.surfaceVariant, if (darkTheme) 0.42f else 0.28f)
    val cardBase = blend(colorScheme.surface, colorScheme.primary, if (darkTheme) 0.06f else 0.03f)
    val screenAlpha = if (hasBackdrop) 0.74f else 1f
    val secondaryAlpha = if (hasBackdrop) 0.82f else 1f
    val cardAlpha = if (hasBackdrop) 0.90f else 1f
    val dialogAlpha = if (hasBackdrop) 0.96f else 1f
    val chipAlpha = if (hasBackdrop) 0.88f else 1f

    return EaseSurfaces(
        screen = screenBase.copy(alpha = screenAlpha),
        secondary = secondaryBase.copy(alpha = secondaryAlpha),
        card = cardBase.copy(alpha = cardAlpha),
        dialog = colorScheme.surface.copy(alpha = dialogAlpha),
        chip = secondaryBase.copy(alpha = chipAlpha),
        shadow = colorScheme.surfaceVariant.copy(alpha = if (darkTheme) 0.52f else 0.28f),
        backdropImageAlpha = if (hasBackdrop) 0.30f else 0f,
        backdropScrim = colorScheme.surface.copy(alpha = if (hasBackdrop) { if (darkTheme) 0.24f else 0.34f } else 0f),
        coverScrim = colorScheme.surface.copy(alpha = if (darkTheme) 0.56f else 0.64f),
        hasBackdrop = hasBackdrop,
    )
}

private val LocalEaseSurfaces = staticCompositionLocalOf {
    EaseSurfaces(
        screen = Color.White,
        secondary = Color.White,
        card = Color.White,
        dialog = Color.White,
        chip = Color.White,
        shadow = Color.Transparent,
        backdropImageAlpha = 0f,
        backdropScrim = Color.Transparent,
        coverScrim = Color.Transparent,
        hasBackdrop = false,
    )
}

object EaseTheme {
    val spacing: EaseSpacing = AppEaseSpacing
    val radius: EaseRadius = AppEaseRadius
    val typography: EaseTypography = AppEaseTypography

    val colors: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    val surfaces: EaseSurfaces
        @Composable get() = LocalEaseSurfaces.current
}

@Composable
fun EaseMusicPlayerTheme(
    themeSettings: ThemeSettings,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = buildColorScheme(themeSettings.primaryColor, darkTheme)
    val surfaces = buildSurfaceTokens(
        colorScheme = colorScheme,
        darkTheme = darkTheme,
        hasBackdrop = !themeSettings.backgroundImageUri.isNullOrBlank(),
    )

    CompositionLocalProvider(
        LocalEaseSurfaces provides surfaces,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
