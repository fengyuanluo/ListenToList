package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.ui.theme.EaseTheme

@Composable
fun SimpleFormText(
    label: String?,
    value: String,
    onChange: (value: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        if (label != null) {
            Text(
                text = label,
                style = EaseTheme.typography.label,
            )
        }
        TextField(
            modifier = Modifier
                .fillMaxWidth(),
            value = value,
            onValueChange = {value -> onChange(value)},
        )
    }
}

@Composable
fun FormWidget(
    label: String,
    block: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = label,
            style = EaseTheme.typography.label,
        )
        block()
    }
}

@Composable
fun FormText(
    label: String,
    value: String,
    onChange: (value: String) -> Unit,
    error: Int? = null,
    isPassword: Boolean = false
) {
    var passwordVisibleState = remember { mutableStateOf(false) }
    val passwordVisible = passwordVisibleState.value

    FormWidget(
        label = label
    ) {
        if (!isPassword) {
            TextField(
                modifier = Modifier
                    .fillMaxWidth(),
                value = value,
                onValueChange = onChange,
                isError = error != null,
            )
        } else {
            TextField(
                modifier = Modifier
                    .fillMaxWidth(),
                value = value,
                onValueChange = onChange,
                isError = error != null,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val painter = if (!passwordVisible) {
                        painterResource(id = R.drawable.icon_visibility)
                    } else {
                        painterResource(id = R.drawable.icon_visibility_off)
                    }

                    IconButton(onClick = {
                        passwordVisibleState.value = !passwordVisible
                    }) {
                        Icon(painter = painter, contentDescription = null)
                    }
                }
            )
        }
        if (error != null) {
            Text(
                text = stringResource(id = error),
                color = MaterialTheme.colorScheme.error,
                style = EaseTheme.typography.micro,
            )
        }
    }
}

@Composable
fun FormSwitch(
    label: String,
    value: Boolean,
    onChange: (value: Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EaseTheme.spacing.xxs)
            .background(
                color = EaseTheme.surfaces.secondary.copy(alpha = 0.72f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(EaseTheme.radius.card),
            )
            .padding(
                horizontal = EaseTheme.spacing.md,
                vertical = EaseTheme.spacing.sm,
            )
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = EaseTheme.typography.label,
            modifier = Modifier.weight(1f),
        )
        EaseFlatSwitch(
            checked = value,
            onCheckedChange = onChange,
        )
    }
}
