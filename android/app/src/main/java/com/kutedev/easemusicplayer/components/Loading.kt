package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.SaltTheme

@Composable
fun EaseLinearProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    trackColor: Color = EaseTheme.surfaces.secondary,
    indicatorColor: Color = SaltTheme.colors.highlight,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(EaseTheme.radius.control))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clampedProgress)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(indicatorColor)
        )
    }
}

@Composable
fun EaseIndeterminateBar(
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    indicatorFraction: Float = 0.38f,
    trackColor: Color = EaseTheme.surfaces.secondary,
    indicatorColor: Color = SaltTheme.colors.highlight,
) {
    val clampedFraction = indicatorFraction.coerceIn(0.1f, 0.9f)

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(EaseTheme.radius.control))
            .background(trackColor)
    ) {
        val leadingSpace = maxWidth * ((1f - clampedFraction) / 2f)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clampedFraction)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(indicatorColor)
                .offset(x = leadingSpace)
        )
    }
}

@Composable
fun EasePulsingDot(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = SaltTheme.colors.highlight,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.88f))
    )
}
