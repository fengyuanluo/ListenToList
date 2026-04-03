package com.kutedev.easemusicplayer.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.ui.theme.EaseTheme

private val EaseFlatSwitchWidth = 46.dp
private val EaseFlatSwitchHeight = 28.dp
private val EaseFlatSwitchThumbSize = 18.dp
private val EaseFlatSwitchPadding = 4.dp

@Composable
fun EaseFlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val thumbOffsetX = animateDpAsState(
        targetValue = if (checked) {
            EaseFlatSwitchWidth - EaseFlatSwitchThumbSize - EaseFlatSwitchPadding
        } else {
            EaseFlatSwitchPadding
        },
        animationSpec = tween(durationMillis = 180),
        label = "flat-switch-thumb-offset",
    )
    val trackColor = animateColorAsState(
        targetValue = when {
            !enabled -> EaseTheme.surfaces.secondary.copy(alpha = 0.42f)
            checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else -> EaseTheme.surfaces.secondary
        },
        animationSpec = tween(durationMillis = 180),
        label = "flat-switch-track-color",
    )
    val borderColor = animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "flat-switch-border-color",
    )
    val thumbColor = animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
            checked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "flat-switch-thumb-color",
    )
    val thumbShadowColor = if (checked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Black.copy(alpha = 0.10f)
    }

    Box(
        modifier = modifier
            .size(width = EaseFlatSwitchWidth, height = EaseFlatSwitchHeight)
            .clip(RoundedCornerShape(EaseTheme.radius.control))
            .background(trackColor.value)
            .border(
                width = 1.dp,
                color = borderColor.value,
                shape = RoundedCornerShape(EaseTheme.radius.control),
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(EaseFlatSwitchThumbSize)
                .offset(x = thumbOffsetX.value)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(thumbColor.value)
                .border(
                    width = 1.dp,
                    color = thumbShadowColor,
                    shape = RoundedCornerShape(EaseTheme.radius.control),
                ),
        )
    }
}
