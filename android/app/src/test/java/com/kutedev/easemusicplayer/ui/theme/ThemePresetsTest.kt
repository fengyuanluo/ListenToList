package com.kutedev.easemusicplayer.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetsTest {
    @Test
    fun paletteColorsAreStable() {
        val colors = buildPaletteColors()
        assertEquals(36, colors.size)
        assertTrue(colors.distinct().size >= 30)
    }

    @Test
    fun presetsNotEmpty() {
        assertTrue(ThemePresets.isNotEmpty())
    }

    @Test
    fun themeSettingsDefaults() {
        val settings = ThemeSettings()
        assertEquals(0xFF2E89B0.toInt(), settings.primaryColor.toArgb())
        assertNull(settings.backgroundImageUri)
    }
}
