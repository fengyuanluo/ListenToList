package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.ItemEdit
import com.moriafly.salt.ui.ItemEditPassword
import com.moriafly.salt.ui.ItemOuterEdit
import com.moriafly.salt.ui.ItemOuterTip
import com.moriafly.salt.ui.ItemOuterTitle
import com.moriafly.salt.ui.ItemSwitcher
import com.moriafly.salt.ui.UnstableSaltUiApi

@OptIn(UnstableSaltUiApi::class)
@Composable
fun SimpleFormText(
    label: String?,
    value: String,
    onChange: (value: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        label?.let { ItemOuterTitle(text = it) }
        ItemOuterEdit(
            text = value,
            onChange = onChange,
        )
    }
}

@Composable
fun FormWidget(
    label: String,
    block: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ItemOuterTitle(text = label)
        block()
    }
}

@OptIn(UnstableSaltUiApi::class)
@Composable
fun FormText(
    label: String,
    value: String,
    onChange: (value: String) -> Unit,
    error: Int? = null,
    isPassword: Boolean = false,
) {
    FormWidget(label = label) {
        if (isPassword) {
            ItemEditPassword(
                text = value,
                onChange = onChange,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ItemEdit(
                text = value,
                onChange = onChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let {
            ItemOuterTip(text = stringResource(id = it))
        }
    }
}

@OptIn(UnstableSaltUiApi::class)
@Composable
fun FormSwitch(
    label: String,
    value: Boolean,
    onChange: (value: Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EaseTheme.spacing.xxs),
    ) {
        ItemSwitcher(
            state = value,
            onChange = onChange,
            text = label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
