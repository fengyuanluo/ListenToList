package com.kutedev.easemusicplayer.widgets.appbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.PlayerVM
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteHome
import com.kutedev.easemusicplayer.core.RoutePlaylist
import com.kutedev.easemusicplayer.core.isRouteHome
import com.kutedev.easemusicplayer.core.isRoutePlaylist
import com.kutedev.easemusicplayer.widgets.musics.MiniPlayer
import com.moriafly.salt.ui.BottomBar as SaltBottomBar
import com.moriafly.salt.ui.BottomBarItem as SaltBottomBarItem
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.UnstableSaltUiApi
import kotlinx.coroutines.launch

internal val BottomNavigationBarHeight = 60.dp
internal val MiniPlayerHeight = 64.dp + EaseTheme.spacing.lg * 2

private interface IBottomItem {
    val painterId: Int
    val pageIndex: Int
    val titleRes: Int
}

private object BPlaylist : IBottomItem {
    override val painterId: Int
        get() = R.drawable.icon_album
    override val pageIndex: Int
        get() = 0
    override val titleRes: Int
        get() = R.string.bottom_bar_playlist
}

private object BDashboard : IBottomItem {
    override val painterId: Int
        get() = R.drawable.icon_dashboard
    override val pageIndex: Int
        get() = 1
    override val titleRes: Int
        get() = R.string.bottom_bar_dashboard
}

private object BSetting : IBottomItem {
    override val painterId: Int
        get() = R.drawable.icon_setting
    override val pageIndex: Int
        get() = 2
    override val titleRes: Int
        get() = R.string.bottom_bar_setting
}

@Composable
fun getBottomBarSpace(
    hasCurrentMusic: Boolean,
    scaffoldPadding: PaddingValues,
): Dp {
    val bottomInset = bottomSafePadding(scaffoldPadding)
    return calculateBottomBarSpace(
        hasCurrentMusic = hasCurrentMusic,
        bottomInset = bottomInset,
    )
}

internal fun calculateBottomBarSpace(
    hasCurrentMusic: Boolean,
    bottomInset: Dp,
): Dp {
    var total = BottomNavigationBarHeight + bottomInset
    if (hasCurrentMusic) {
        total += MiniPlayerHeight
    }
    return total
}

@Composable
private fun bottomSafePadding(scaffoldPadding: PaddingValues): Dp {
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scaffoldBottom = scaffoldPadding.calculateBottomPadding()
    return if (scaffoldBottom > navPadding) scaffoldBottom else navPadding
}

@Composable
fun BottomBarSpacer(
    hasCurrentMusic: Boolean,
    scaffoldPadding: PaddingValues,
) {
    Box(modifier = Modifier.height(getBottomBarSpace(hasCurrentMusic, scaffoldPadding)))
}

@OptIn(UnstableSaltUiApi::class)
@Composable
fun BoxScope.BottomBar(
    bottomBarPageState: PagerState?,
    scaffoldPadding: PaddingValues,
    playerVM: PlayerVM = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val current by playerVM.music.collectAsState()
    val items = listOf(BPlaylist, BDashboard, BSetting)
    val animationScope = rememberCoroutineScope()

    val hasCurrentMusic = current?.meta?.id != null
    val showBottomBar = isRouteHome(currentRoute)
    val showMiniPlayer = hasCurrentMusic && (isRouteHome(currentRoute) || isRoutePlaylist(currentRoute))
    val bottomInset = bottomSafePadding(scaffoldPadding)

    if (!showBottomBar && !showMiniPlayer) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(scaffoldPadding.calculateBottomPadding()),
        )
        return
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .background(EaseTheme.surfaces.card)
            .fillMaxWidth(),
    ) {
        if (showMiniPlayer) {
            MiniPlayer()
        }
        if (showBottomBar && bottomBarPageState != null) {
            SaltBottomBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BottomNavigationBarHeight),
                backgroundColor = SaltTheme.colors.subBackground,
            ) {
                items.forEach { item ->
                    val isSelected = bottomBarPageState.currentPage == item.pageIndex
                    SaltBottomBarItem(
                        state = isSelected,
                        onClick = {
                            animationScope.launch {
                                bottomBarPageState.animateScrollToPage(item.pageIndex)
                            }
                        },
                        painter = painterResource(id = item.painterId),
                        text = stringResource(id = item.titleRes),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomInset),
        )
    }
}
