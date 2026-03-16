package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kutedev.easemusicplayer.R

@Composable
fun EaseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val shape = RoundedCornerShape(24.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val backgroundColor = if (focused) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
    }
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    }
    val textStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 15.sp,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                offsetX = 0.dp,
                offsetY = 6.dp,
                blurRadius = 18.dp,
            )
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_search),
                    contentDescription = null,
                    tint = if (focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp)
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotBlank()) {
                    EaseIconButton(
                        sizeType = EaseIconButtonSize.Small,
                        buttonType = EaseIconButtonType.Default,
                        painter = painterResource(id = R.drawable.icon_close),
                        onClick = onClear,
                    )
                }
            }
        }
    )
}
