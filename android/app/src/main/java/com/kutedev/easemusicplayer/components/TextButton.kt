package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ButtonType
import com.moriafly.salt.ui.SaltTheme

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
    val textStyle: TextStyle,
)

@Composable
private fun resolveEaseTextButtonMetrics(size: EaseTextButtonSize): EaseTextButtonMetrics {
    return when (size) {
        EaseTextButtonSize.Medium -> EaseTextButtonMetrics(
            minHeight = 38.dp,
            textStyle = EaseTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
        )

        EaseTextButtonSize.Small -> EaseTextButtonMetrics(
            minHeight = 30.dp,
            textStyle = EaseTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
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
    val (buttonType, borderColor) = when (type) {
        EaseTextButtonType.Primary -> ButtonType.Highlight to null
        EaseTextButtonType.PrimaryVariant -> ButtonType.Sub to SaltTheme.colors.highlight.copy(alpha = 0.24f)
        EaseTextButtonType.Default -> ButtonType.Sub to SaltTheme.colors.stroke
        EaseTextButtonType.Error -> ButtonType.Sub to Color(0xFFD24545)
    }

    Button(
        onClick = onClick,
        text = text,
        enabled = !disabled,
        type = buttonType,
        maxLines = 1,
        modifier = modifier
            .defaultMinSize(minHeight = metrics.minHeight)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, SaltTheme.shapes.medium)
                } else {
                    Modifier
                },
            ),
    )
}
