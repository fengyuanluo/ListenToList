package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.kutedev.easemusicplayer.ui.theme.EaseTheme

enum class EaseTextButtonType {
    Primary,
    PrimaryVariant,
    Error,
    Default,
}

enum class EaseTextButtonSize {
    Medium,
    Small,
}

private data class EaseTextButtonMetrics(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val textStyle: TextStyle,
)

private data class EaseTextButtonPalette(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color?,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
    val disabledBorderColor: Color?,
)

private fun blend(base: Color, overlay: Color, amount: Float): Color {
    return Color(
        ColorUtils.blendARGB(base.toArgb(), overlay.toArgb(), amount.coerceIn(0f, 1f)),
    )
}

@Composable
private fun resolveEaseTextButtonMetrics(size: EaseTextButtonSize): EaseTextButtonMetrics {
    return when (size) {
        EaseTextButtonSize.Medium -> EaseTextButtonMetrics(
            minHeight = 38.dp,
            horizontalPadding = 12.dp,
            verticalPadding = 9.dp,
            textStyle = EaseTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
        )

        EaseTextButtonSize.Small -> EaseTextButtonMetrics(
            minHeight = 30.dp,
            horizontalPadding = 10.dp,
            verticalPadding = 6.dp,
            textStyle = EaseTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun resolveEaseTextButtonPalette(type: EaseTextButtonType): EaseTextButtonPalette {
    val chipBase = EaseTheme.surfaces.chip.copy(alpha = 1f)
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant

    return when (type) {
        EaseTextButtonType.Primary -> EaseTextButtonPalette(
            containerColor = blend(chipBase, primary, 0.88f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            borderColor = primary.copy(alpha = 0.14f),
            disabledContainerColor = blend(chipBase, primary, 0.24f),
            disabledContentColor = onSurfaceVariant.copy(alpha = 0.62f),
            disabledBorderColor = primary.copy(alpha = 0.08f),
        )

        EaseTextButtonType.PrimaryVariant -> EaseTextButtonPalette(
            containerColor = blend(chipBase, primary, 0.16f),
            contentColor = primary,
            borderColor = primary.copy(alpha = 0.18f),
            disabledContainerColor = blend(chipBase, primary, 0.08f),
            disabledContentColor = onSurfaceVariant.copy(alpha = 0.58f),
            disabledBorderColor = primary.copy(alpha = 0.10f),
        )

        EaseTextButtonType.Error -> EaseTextButtonPalette(
            containerColor = blend(chipBase, error, 0.15f),
            contentColor = error,
            borderColor = error.copy(alpha = 0.20f),
            disabledContainerColor = blend(chipBase, error, 0.08f),
            disabledContentColor = onSurfaceVariant.copy(alpha = 0.58f),
            disabledBorderColor = error.copy(alpha = 0.10f),
        )

        EaseTextButtonType.Default -> EaseTextButtonPalette(
            containerColor = chipBase,
            contentColor = onSurface.copy(alpha = 0.96f),
            borderColor = outline.copy(alpha = 0.30f),
            disabledContainerColor = chipBase.copy(alpha = 0.60f),
            disabledContentColor = onSurfaceVariant.copy(alpha = 0.56f),
            disabledBorderColor = outline.copy(alpha = 0.18f),
        )
    }
}

@Composable
fun EaseTextButton(
    text: String,
    type: EaseTextButtonType,
    size: EaseTextButtonSize,
    onClick: () -> Unit,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val metrics = resolveEaseTextButtonMetrics(size)
    val palette = resolveEaseTextButtonPalette(type)
    val shape = RoundedCornerShape(EaseTheme.radius.control)
    val containerColor = if (disabled) palette.disabledContainerColor else palette.containerColor
    val contentColor = if (disabled) palette.disabledContentColor else palette.contentColor
    val borderColor = if (disabled) palette.disabledBorderColor else palette.borderColor

    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = 0.dp,
                minHeight = metrics.minHeight,
            )
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(
                        width = 1.dp,
                        color = borderColor,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = !disabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = metrics.horizontalPadding,
                vertical = metrics.verticalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = metrics.textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
