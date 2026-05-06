package com.kutedev.easemusicplayer.widgets.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteDebugMore
import com.kutedev.easemusicplayer.core.RouteDownloadManager
import com.kutedev.easemusicplayer.core.RouteLrcApiSettings
import com.kutedev.easemusicplayer.core.RouteLog
import com.kutedev.easemusicplayer.core.RouteThemeSettings
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.ItemArrowType
import com.moriafly.salt.ui.ItemOuterSpacer
import com.moriafly.salt.ui.ItemOuterTitle

private val paddingX = SettingPaddingX

@Composable
fun SettingSubpage() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val gitUrl = "https://github.com/fengyuanluo/ListenToList"
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingX)
            .verticalScroll(rememberScrollState()),
    ) {
        ItemOuterSpacer()
        ItemOuterTitle(text = stringResource(id = R.string.setting_theme_entry_title))
        Item(
            onClick = { navController.navigate(RouteThemeSettings()) },
            text = stringResource(id = R.string.setting_theme_entry_title),
            iconPainter = painterResource(R.drawable.icon_adjust),
            sub = stringResource(id = R.string.setting_theme_entry_desc),
        )
        Item(
            onClick = { navController.navigate(RouteLrcApiSettings()) },
            text = stringResource(id = R.string.setting_lrcapi_entry_title),
            iconPainter = painterResource(R.drawable.icon_lyrics),
            sub = stringResource(id = R.string.setting_lrcapi_entry_desc),
        )
        Item(
            onClick = { navController.navigate(RouteDownloadManager()) },
            text = stringResource(id = R.string.setting_downloads_title),
            iconPainter = painterResource(R.drawable.icon_download),
            sub = stringResource(id = R.string.setting_downloads_desc),
        )
        ItemOuterSpacer()
        ItemOuterTitle(text = stringResource(id = R.string.setting_debug))
        Item(
            onClick = { navController.navigate(RouteLog()) },
            text = stringResource(id = R.string.setting_log),
            iconPainter = painterResource(R.drawable.icon_log),
            arrowType = ItemArrowType.Arrow,
        )
        Item(
            onClick = { navController.navigate(RouteDebugMore()) },
            text = stringResource(id = R.string.setting_more),
            iconPainter = painterResource(R.drawable.icon_vertialcal_more),
            arrowType = ItemArrowType.Arrow,
        )
        ItemOuterSpacer()
        ItemOuterTitle(text = stringResource(id = R.string.setting_about))
        Item(
            onClick = { uriHandler.openUri(gitUrl) },
            text = stringResource(id = R.string.setting_git_repo),
            iconPainter = painterResource(R.drawable.icon_github),
            sub = gitUrl,
        )
        Item(
            onClick = {},
            text = stringResource(id = R.string.setting_version),
            iconPainter = painterResource(R.drawable.icon_info),
            sub = getAppVersion(context),
            arrowType = ItemArrowType.None,
        )
        ItemOuterSpacer()
    }
}
