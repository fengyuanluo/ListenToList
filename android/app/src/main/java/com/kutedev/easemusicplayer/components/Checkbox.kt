package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.R
import com.moriafly.salt.ui.SaltTheme

@Composable
fun EaseCheckbox(
    value: Boolean,
    onChange: (value: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (value) SaltTheme.colors.highlight else SaltTheme.colors.subBackground)
            .border(
                width = 1.dp,
                color = if (value) SaltTheme.colors.highlight else SaltTheme.colors.stroke,
                shape = shape,
            )
            .clickable { onChange(!value) },
        contentAlignment = Alignment.Center,
    ) {
        if (value) {
            Image(
                painter = painterResource(id = R.drawable.icon_yes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SaltTheme.colors.onHighlight),
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
