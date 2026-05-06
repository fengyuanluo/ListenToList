package com.kutedev.easemusicplayer.widgets.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.TitleBar
import com.moriafly.salt.ui.UnstableSaltUiApi

val SettingPaddingX = EaseTheme.spacing.page

fun getAppVersion(
    context: android.content.Context,
): String {
    val packageManager = context.packageManager
    val packageName = context.packageName
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
    return packageInfo.versionName ?: "<unknown>"
}

@OptIn(UnstableSaltUiApi::class)
@Composable
fun SettingsSubpageScaffold(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val navController = LocalNavController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EaseTheme.surfaces.screen),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            TitleBar(
                onBack = { navController.popBackStack() },
                text = title,
                showBackBtn = true,
            )
            trailing?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = EaseTheme.spacing.xxs)
                        .height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    it()
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}
