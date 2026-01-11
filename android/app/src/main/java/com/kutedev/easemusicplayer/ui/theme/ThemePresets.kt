package com.kutedev.easemusicplayer.ui.theme

import androidx.compose.ui.graphics.Color


data class ThemePreset(
    val name: String,
    val color: Color,
)

val ThemePresets = listOf(
    ThemePreset("深海蓝", Color(0xFF2E89B0)),
    ThemePreset("海盐青", Color(0xFF2A9D8F)),
    ThemePreset("熔岩红", Color(0xFFE4572E)),
    ThemePreset("琥珀橙", Color(0xFFF4A261)),
    ThemePreset("紫罗兰", Color(0xFF7B61FF)),
    ThemePreset("夜幕灰", Color(0xFF546E7A)),
    ThemePreset("松林绿", Color(0xFF2E7D32)),
    ThemePreset("薄荷绿", Color(0xFF4CAF50)),
)

fun buildPaletteColors(): List<Color> {
    val hues = listOf(0f, 20f, 40f, 60f, 90f, 120f, 160f, 200f, 230f, 260f, 300f, 330f)
    val values = listOf(0.65f, 0.8f, 0.95f)
    val colors = mutableListOf<Color>()
    for (value in values) {
        for (hue in hues) {
            colors.add(Color.hsv(hue, 0.8f, value))
        }
    }
    return colors
}
