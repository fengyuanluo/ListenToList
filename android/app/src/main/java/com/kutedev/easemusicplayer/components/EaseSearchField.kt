package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.R
import com.moriafly.salt.ui.ItemEdit
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.UnstableSaltUiApi

@OptIn(UnstableSaltUiApi::class)
@Composable
fun EaseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevated: Boolean = true,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val borderModifier = if (elevated) {
        Modifier.border(1.dp, SaltTheme.colors.stroke, SaltTheme.shapes.medium)
    } else {
        Modifier
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier),
    ) {
        Image(
            painter = painterResource(id = R.drawable.icon_search),
            contentDescription = null,
            colorFilter = ColorFilter.tint(SaltTheme.colors.subText),
            modifier = Modifier
                .padding(start = SaltTheme.dimens.padding, end = SaltTheme.dimens.subPadding)
                .size(18.dp),
        )
        ItemEdit(
            text = value,
            onChange = onValueChange,
            hint = placeholder,
            readOnly = !enabled,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = { onSearch() },
            ),
            actionContent = {
                if (value.isNotBlank()) {
                    Box(
                        modifier = Modifier.padding(end = SaltTheme.dimens.subPadding),
                    ) {
                        EaseIconButton(
                            sizeType = EaseIconButtonSize.Small,
                            buttonType = EaseIconButtonType.Default,
                            painter = painterResource(id = R.drawable.icon_close),
                            onClick = onClear,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
