package com.kutedev.easemusicplayer.widgets.appbar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarSpaceTest {
    @Test
    fun calculateBottomBarSpace_matchesRenderedChromeHeights() {
        assertEquals(
            60.dp + 8.dp,
            calculateBottomBarSpace(hasCurrentMusic = false, bottomInset = 8.dp),
        )
        assertEquals(
            60.dp + (64.dp + 20.dp * 2) + 8.dp,
            calculateBottomBarSpace(hasCurrentMusic = true, bottomInset = 8.dp),
        )
    }
}
