package com.kutedev.easemusicplayer.widgets.playlists

import EaseImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseSearchField
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RoutePlaylist
import com.kutedev.easemusicplayer.core.RouteStorageSearch
import com.kutedev.easemusicplayer.singleton.PlaylistDisplayMode
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.viewmodels.CreatePlaylistVM
import com.kutedev.easemusicplayer.viewmodels.PlaylistsMode
import com.kutedev.easemusicplayer.viewmodels.PlaylistsVM
import com.kutedev.easemusicplayer.viewmodels.durationStr
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ScrollMoveMode
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import uniffi.ease_client_backend.PlaylistAbstract

private val playlistsPaddingX = EaseTheme.spacing.page

@Composable
internal fun PlaylistHomeSearchEntry(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EaseSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(id = R.string.storage_search_placeholder_home),
        elevated = false,
        onSearch = onSearch,
        onClear = onClearQuery,
        modifier = modifier
            .fillMaxWidth()
            .testTag("playlist_home_search_entry")
    )
}

@Composable
private fun playlistContentBottomPadding(mode: PlaylistsMode): Dp {
    return if (mode == PlaylistsMode.Adjust) 104.dp else EaseTheme.spacing.xl
}

@Composable
private fun PlaylistCover(
    playlist: PlaylistAbstract,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(EaseTheme.surfaces.secondary)
    ) {
        val cover = playlist.meta.showCover
        if (cover == null) {
            Image(
                modifier = Modifier.fillMaxSize(),
                painter = painterResource(id = R.drawable.cover_default_image),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
            )
        } else {
            EaseImage(
                modifier = Modifier.fillMaxSize(),
                dataSourceKey = cover,
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
fun PlaylistsSubpage(
    playlistsVM: PlaylistsVM = hiltViewModel(),
    editPlaylistVM: CreatePlaylistVM = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val playlists by playlistsVM.playlists.collectAsState()
    val playlistsMode by playlistsVM.mode.collectAsState()
    val displayMode by playlistsVM.displayMode.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    if (playlists.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EaseTheme.surfaces.screen)
        ) {
            Spacer(modifier = Modifier.height(EaseTheme.spacing.lg))
            PlaylistHomeSearchEntry(
                query = searchQuery,
                onQueryChange = { value -> searchQuery = value },
                onSearch = { navController.navigate(RouteStorageSearch(searchQuery)) },
                onClearQuery = { searchQuery = "" },
                modifier = Modifier.padding(horizontal = playlistsPaddingX),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(EaseTheme.radius.card))
                        .background(EaseTheme.surfaces.secondary)
                        .padding(EaseTheme.spacing.dialogPadding),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.empty_playlists),
                        contentDescription = null,
                    )
                    Box(modifier = Modifier.height(EaseTheme.spacing.lg))
                    Text(text = stringResource(id = R.string.playlist_empty))
                    Box(modifier = Modifier.height(EaseTheme.spacing.sm))
                    EaseTextButton(
                        text = stringResource(id = R.string.playlist_empty_action),
                        type = EaseTextButtonType.Primary,
                        size = EaseTextButtonSize.Medium,
                        onClick = { editPlaylistVM.openModal() },
                    )
                }
            }
        }
        return
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(EaseTheme.surfaces.screen)
        ) {
            Spacer(modifier = Modifier.height(EaseTheme.spacing.lg))
            PlaylistHomeSearchEntry(
                query = searchQuery,
                onQueryChange = { value -> searchQuery = value },
                onSearch = { navController.navigate(RouteStorageSearch(searchQuery)) },
                onClearQuery = { searchQuery = "" },
                modifier = Modifier.padding(horizontal = playlistsPaddingX),
            )
            Row(
                modifier = Modifier
                    .padding(
                        start = playlistsPaddingX,
                        end = playlistsPaddingX,
                        top = EaseTheme.spacing.xs + 2.dp,
                        bottom = EaseTheme.spacing.xs,
                    )
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_adjust),
                    disabled = playlistsMode == PlaylistsMode.Adjust,
                    onClick = { playlistsVM.toggleMode() },
                )
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_plus),
                    disabled = playlistsMode == PlaylistsMode.Adjust,
                    onClick = { editPlaylistVM.openModal() },
                )
            }

            when (displayMode) {
                PlaylistDisplayMode.Grid -> GridPlaylists(
                    modifier = Modifier.weight(1f),
                    bottomPadding = playlistContentBottomPadding(playlistsMode),
                )
                PlaylistDisplayMode.List -> ListPlaylists(
                    modifier = Modifier.weight(1f),
                    bottomPadding = playlistContentBottomPadding(playlistsMode),
                )
            }
        }

        if (playlistsMode == PlaylistsMode.Adjust) {
            FloatingActionButton(
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
                onClick = {
                    playlistsVM.setMode(PlaylistsMode.Normal)
                },
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_yes),
                    tint = Color.White,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun GridPlaylists(
    modifier: Modifier = Modifier,
    bottomPadding: Dp,
    playlistsVM: PlaylistsVM = hiltViewModel(),
) {
    val playlists by playlistsVM.playlists.collectAsState()
    val lazyGridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = lazyGridState,
        scrollMoveMode = ScrollMoveMode.INSERT,
    ) { from, to ->
        playlistsVM.moveTo(from.index, to.index)
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxWidth(),
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.Center,
        state = lazyGridState,
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        items(playlists, key = { it.meta.id.value }) { playlist ->
            ReorderableItem(reorderableState, key = playlist.meta.id.value) { isDragging ->
                PlaylistGridItem(
                    playlist = playlist,
                    isDragging = isDragging,
                )
            }
        }
    }
}

@Composable
private fun ListPlaylists(
    modifier: Modifier = Modifier,
    bottomPadding: Dp,
    playlistsVM: PlaylistsVM = hiltViewModel(),
) {
    val playlists by playlistsVM.playlists.collectAsState()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
        playlistsVM.moveTo(from.index, to.index)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        contentPadding = PaddingValues(
            start = playlistsPaddingX,
            end = playlistsPaddingX,
            top = EaseTheme.spacing.xs,
            bottom = bottomPadding,
        ),
    ) {
        items(playlists, key = { it.meta.id.value }) { playlist ->
            ReorderableItem(reorderableState, key = playlist.meta.id.value) { isDragging ->
                PlaylistListItem(
                    playlist = playlist,
                    isDragging = isDragging,
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.PlaylistGridItem(
    playlist: PlaylistAbstract,
    isDragging: Boolean,
    playlistsVM: PlaylistsVM = hiltViewModel(),
) {
    val mode by playlistsVM.mode.collectAsState()
    val navController = LocalNavController.current
    val shape = RoundedCornerShape(EaseTheme.radius.hero)
    val dragModifier = if (isDragging) {
        Modifier
            .shadow(8.dp, shape)
            .scale(1.02f)
            .alpha(0.95f)
    } else {
        Modifier
    }

    Box(
        modifier = dragModifier.then(
            if (mode == PlaylistsMode.Adjust) {
                Modifier.draggableHandle(
                    onDragStopped = { playlistsVM.commitMove() },
                )
            } else {
                Modifier.clickable {
                    navController.navigate(RoutePlaylist(playlist.meta.id.value.toString()))
                }
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            PlaylistCover(
                playlist = playlist,
                modifier = Modifier.size(136.dp),
                shape = shape,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = playlist.meta.title,
                    style = EaseTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = buildAnnotatedString {
                    append("${playlist.musicCount} ${stringResource(id = R.string.music_count_unit)}")
                    append("  ·  ")
                    append(playlist.durationStr())
                },
                style = EaseTheme.typography.bodySmall.copy(fontWeight = FontWeight.Light),
                maxLines = 1,
            )
        }

        if (mode == PlaylistsMode.Adjust) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(24.dp)
                    .clip(RoundedCornerShape(EaseTheme.radius.xs))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    modifier = Modifier.size(12.dp),
                    painter = painterResource(id = R.drawable.icon_drag),
                    tint = Color.White,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.PlaylistListItem(
    playlist: PlaylistAbstract,
    isDragging: Boolean,
    playlistsVM: PlaylistsVM = hiltViewModel(),
) {
    val mode by playlistsVM.mode.collectAsState()
    val navController = LocalNavController.current
    val shape = RoundedCornerShape(EaseTheme.radius.card)
    val dragModifier = if (isDragging) {
        Modifier
            .shadow(10.dp, shape)
            .scale(1.01f)
            .alpha(0.97f)
    } else {
        Modifier
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .clip(shape)
            .background(EaseTheme.surfaces.secondary)
            .then(
                if (mode == PlaylistsMode.Adjust) {
                    Modifier
                } else {
                    Modifier.clickable {
                        navController.navigate(RoutePlaylist(playlist.meta.id.value.toString()))
                    }
                }
            )
            .padding(EaseTheme.spacing.sm)
    ) {
        PlaylistCover(
            playlist = playlist,
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(EaseTheme.radius.compact),
        )
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = playlist.meta.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.sectionTitle.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = (-2).dp),
            )
            Text(
                text = buildAnnotatedString {
                    append("${playlist.musicCount} ${stringResource(id = R.string.music_count_unit)}")
                    append("  ·  ")
                    append(playlist.durationStr())
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = EaseTheme.spacing.xxs + 2.dp),
            )
        }
        if (mode == PlaylistsMode.Adjust) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(EaseTheme.surfaces.card)
                    .draggableHandle(
                        onDragStopped = { playlistsVM.commitMove() },
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_drag),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
