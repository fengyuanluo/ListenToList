package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.easeIconButtonSizeToDp
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.ui.theme.EaseTheme

val SettingPaddingX = EaseTheme.spacing.page

private val SettingsTopBarHorizontalPadding = EaseTheme.spacing.md
private val SettingsTopBarVerticalPadding = EaseTheme.spacing.sm
private val SettingsTopBarActionSlotSize = easeIconButtonSizeToDp(EaseIconButtonSize.Medium)

@Composable
fun SettingsSubpageScaffold(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EaseTheme.surfaces.screen)
    ) {
        SettingsSubpageTopBar(
            title = title,
            trailing = trailing,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SettingsSubpageTopBar(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    val navController = LocalNavController.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsTopBarHorizontalPadding,
                vertical = SettingsTopBarVerticalPadding,
            )
    ) {
        Box(
            modifier = Modifier.size(SettingsTopBarActionSlotSize),
            contentAlignment = Alignment.Center,
        ) {
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = EaseIconButtonType.Default,
                painter = painterResource(id = R.drawable.icon_back),
                onClick = { navController.popBackStack() },
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = EaseTheme.typography.sectionTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(SettingsTopBarActionSlotSize),
            contentAlignment = Alignment.Center,
        ) {
            trailing?.invoke()
        }
    }
}
