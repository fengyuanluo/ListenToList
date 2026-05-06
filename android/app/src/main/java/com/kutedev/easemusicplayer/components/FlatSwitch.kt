package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moriafly.salt.ui.Switcher

@Composable
fun EaseFlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switcher(
        state = checked,
        modifier = modifier.clickable(enabled = enabled) {
            onCheckedChange(!checked)
        },
    )
}
