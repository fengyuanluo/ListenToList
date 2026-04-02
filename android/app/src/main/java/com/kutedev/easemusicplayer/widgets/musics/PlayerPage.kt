package com.kutedev.easemusicplayer.widgets.musics

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseContextMenu
import com.kutedev.easemusicplayer.components.EaseContextMenuItem
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonColors
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.components.MusicCover
import com.kutedev.easemusicplayer.components.customAnchoredDraggable
import com.kutedev.easemusicplayer.components.dropShadow
import com.kutedev.easemusicplayer.components.rememberCustomAnchoredDraggableState
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.utils.nextTickOnMain
import com.kutedev.easemusicplayer.viewmodels.LrcApiVM
import com.kutedev.easemusicplayer.viewmodels.PlayerVM
import com.kutedev.easemusicplayer.viewmodels.SleepModeVM
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteLrcApiSettings
import com.kutedev.easemusicplayer.singleton.PlaybackContextType
import com.kutedev.easemusicplayer.singleton.PlaybackQueueEntry
import com.kutedev.easemusicplayer.singleton.PlaybackQueueSnapshot
import com.kutedev.easemusicplayer.singleton.isReadyForFetch
import com.kutedev.easemusicplayer.utils.formatDuration
import com.kutedev.easemusicplayer.utils.resolveLyricIndex
import com.kutedev.easemusicplayer.utils.toMusicDurationMs
import uniffi.ease_client_schema.DataSourceKey
import uniffi.ease_client_backend.LyricLine
import uniffi.ease_client_backend.LyricLoadState
import uniffi.ease_client_backend.Playlist
import uniffi.ease_client_schema.PlayMode
import java.time.Duration
import kotlin.collections.emptyList
import kotlin.math.absoluteValue
import kotlin.math.sign
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
private fun MusicPlayerHeader(
    hasRemovableLyric: Boolean,
    lrcApiReady: Boolean,
    onRetryLrcApiLyric: () -> Unit,
    onOpenLrcApiSettings: () -> Unit,
    playerVM: PlayerVM = hiltViewModel(),
) {
    val navController = LocalNavController.current

    var moreMenuExpanded by remember {
        mutableStateOf(false)
    }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .padding(EaseTheme.spacing.sm)
                .fillMaxWidth()
        ) {
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_back),
            onClick = {
                navController.popBackStack()
            }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_vertialcal_more),
                    onClick = { moreMenuExpanded = true; }
                )
                Box(
                    contentAlignment = Alignment.TopEnd,
                    modifier = Modifier
                        .offset(20.dp, (20).dp)
                ) {
                    EaseContextMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false; },
                        items = listOf(
                            if (hasRemovableLyric) {
                                EaseContextMenuItem(
                                    stringId = R.string.music_lyric_remove,
                                    onClick = {
                                        playerVM.removeLyric()
                                    }
                                )
                            } else {
                                EaseContextMenuItem(
                                    stringId = if (lrcApiReady) {
                                        R.string.music_lyric_fetch_from_lrcapi
                                    } else {
                                        R.string.music_lyric_open_lrcapi_settings
                                    },
                                    onClick = {
                                        if (lrcApiReady) {
                                            onRetryLrcApiLyric()
                                        } else {
                                            onOpenLrcApiSettings()
                                        }
                                    }
                                )
                            },
                            EaseContextMenuItem(
                                stringId = R.string.music_player_context_menu_remove_from_queue,
                                isError = true,
                                onClick = {
                                    playerVM.remove()
                                }
                            ),
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun MusicSlider(
    currentDuration: String,
    _currentDurationMS: ULong,
    bufferDurationMS: ULong,
    totalDuration: String,
    totalDurationMS: ULong,
    onChangeMusicPosition: (ms: ULong) -> Unit,
) {
    val handleSize = 12.dp
    val sliderHeight = 4.dp
    val sliderContainerHeight = 16.dp

    var isDragging by remember { mutableStateOf(false) }
    var draggingCurrentDurationMS by remember { mutableStateOf(_currentDurationMS) }
    val currentDurationMS = if (isDragging) {
        draggingCurrentDurationMS
    } else {
        _currentDurationMS
    }

    val durationRate = if (totalDurationMS == 0UL) {
        0f
    } else {
        (currentDurationMS.toDouble() / totalDurationMS.toDouble()).toFloat()
    };
    val bufferRate = if (totalDurationMS == 0UL) {
        0f
    } else {
        (bufferDurationMS.toDouble() / totalDurationMS.toDouble()).toFloat()
    };
    var sliderWidth by remember { mutableIntStateOf(0) }
    val sliderWidthDp = with(LocalDensity.current) {
        sliderWidth.toDp()
    }

    val draggableState = rememberDraggableState { deltaPx ->
        if (sliderWidth <= 0 || totalDurationMS == 0UL) {
            return@rememberDraggableState
        }
        val delta = (deltaPx.toDouble() / sliderWidth.toDouble() * totalDurationMS.toDouble()).toLong()
        var nextMS = draggingCurrentDurationMS.toLong() + delta
        nextMS = nextMS.coerceIn(0L, totalDurationMS.toLong())

        draggingCurrentDurationMS = nextMS.toULong()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sliderContainerHeight)
                .onSizeChanged { size ->
                    if (sliderWidth != size.width) {
                        sliderWidth = size.width;
                    }
                }
                .pointerInput(totalDurationMS, sliderWidth) {
                    detectTapGestures { offset ->
                        if (sliderWidth <= 0 || totalDurationMS == 0UL) {
                            return@detectTapGestures
                        }
                        var nextMS =
                            (offset.x.toDouble() / sliderWidth.toDouble() * totalDurationMS.toDouble()).toLong()
                        nextMS = nextMS.coerceIn(0L, totalDurationMS.toLong())
                        onChangeMusicPosition(nextMS.toULong())
                    }
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        isDragging = true
                        draggingCurrentDurationMS = _currentDurationMS
                    },
                    onDragStopped = {
                        isDragging = false
                        onChangeMusicPosition(draggingCurrentDurationMS)
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sliderHeight)
                    .offset(0.dp, (sliderContainerHeight - sliderHeight) / 2)
                    .clip(RoundedCornerShape(EaseTheme.radius.sm))
                    .background(EaseTheme.surfaces.secondary)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferRate)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(durationRate)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Box(
                modifier = Modifier
                    .offset(
                        -handleSize / 2 + (sliderWidthDp * durationRate),
                        (sliderContainerHeight - handleSize) / 2
                    )
                    .size(handleSize)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(
                text = currentDuration,
                style = EaseTheme.typography.micro
            )
            Text(
                text = totalDuration,
                style = EaseTheme.typography.micro
            )
        }
    }
}


@Composable
private fun CoverImage(dataSourceKey: DataSourceKey?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
    ) {
        MusicCover(
            modifier = Modifier
                .offset(y = (-10).dp)
                .dropShadow(
                    color = EaseTheme.surfaces.shadow,
                    offsetX = 0.dp,
                    offsetY = 0.dp,
                    blurRadius = 16.dp
                )
                .clip(RoundedCornerShape(EaseTheme.radius.hero))
                .size(300.dp),
            coverDataSourceKey = dataSourceKey,
        )
    }
}

@Composable
private fun MusicLyric(
    lyrics: List<LyricLine>,
    lyricIndex: Int,
    lyricLoadedState: LyricLoadState,
    emptyActionLabel: String,
    onClickEmptyAction: () -> Unit,
    widgetHeight: Int,
) {
    val density = LocalDensity.current
    val widgetHeightDp = with(density) {
        widgetHeight.toDp()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(lyricIndex, widgetHeight, lyricLoadedState) {
        if (lyricLoadedState == LyricLoadState.LOADED) {
            listState.animateScrollToItem(lyricIndex + 1, -(widgetHeight / 2))
        }
    }

    if (widgetHeight == 0) {
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(EaseTheme.spacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        if (lyricLoadedState == LyricLoadState.MISSING || lyricLoadedState == LyricLoadState.FAILED) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    modifier = Modifier.size(64.dp),
                    painter = painterResource(R.drawable.icon_lyrics),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surfaceVariant
                )
                Box(modifier = Modifier.height(4.dp))
                if (lyricLoadedState == LyricLoadState.MISSING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.music_lyric_no_desc),
                            style = EaseTheme.typography.body,
                        )
                        EaseTextButton(
                            text = emptyActionLabel,
                            type = EaseTextButtonType.Primary,
                            size = EaseTextButtonSize.Medium,
                            onClick = {
                                onClickEmptyAction()
                            }
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.music_lyric_fail),
                            style = EaseTheme.typography.body,
                        )
                        EaseTextButton(
                            text = emptyActionLabel,
                            type = EaseTextButtonType.Primary,
                            size = EaseTextButtonSize.Medium,
                            onClick = onClickEmptyAction,
                        )
                    }
                }
            }
            return
        }

        if (lyricLoadedState == LyricLoadState.LOADING) {
            return
        }

        Column {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth(),
                userScrollEnabled = false,
            ) {
                item {
                    Box(modifier = Modifier.height(widgetHeightDp / 2))
                }
                itemsIndexed(lyrics) { index, lyric ->
                    val isCurrent = index == lyricIndex
                    val textColor =
                        if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = lyric.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicPlayerBody(
    onPrev: () -> Unit,
    onNext: () -> Unit,
    cover: DataSourceKey?,
    prevCover: DataSourceKey?,
    nextCover: DataSourceKey?,
    canPrev: Boolean,
    canNext: Boolean,
    showLyric: Boolean,
    lyricIndex: Int,
    lyrics: List<LyricLine>,
    lyricLoadedState: LyricLoadState,
    emptyActionLabel: String,
    onClickLyricAction: () -> Unit,
    onResetLyric: () -> Unit,
) {
    val density = LocalDensity.current
    val anchoredDraggableState = rememberCustomAnchoredDraggableState(
        initialValue = 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = LinearOutSlowInEasing
        ),
        anchors = mapOf(0f to "DEFAULT"),
    )
    val deltaDp = with(density) {
        anchoredDraggableState.value.toDp()
    }
    var widgetWidth by remember { mutableIntStateOf(0) }
    val widgetWidthDp = with(LocalDensity.current) {
        widgetWidth.toDp()
    }
    var widgetHeight by remember { mutableIntStateOf(0) }

    var dragStartX by remember { mutableFloatStateOf(0f) }
    fun updateAnchored() {
        val anchors = listOfNotNull(
            0f to "DEFAULT",
            if (canPrev) {
                widgetWidth.toFloat() to "PREV"
            } else null,
            if (canNext) {
                -widgetWidth.toFloat() to "NEXT"
            } else null,
        ).toMap()

        anchoredDraggableState.updateAnchors(
            anchors,
            { value ->
                if (value == widgetWidth.toFloat()) {
                    nextTickOnMain {
                        onPrev()
                        anchoredDraggableState.update(0f)
                        onResetLyric()
                    }
                } else if (value == -widgetWidth.toFloat()) {
                    nextTickOnMain {
                        onNext()
                        anchoredDraggableState.update(0f)
                        onResetLyric()
                    }
                }
            }
        )
    }

    LaunchedEffect(canPrev, canNext) {
        updateAnchored()
    }

    Box(
        modifier = Modifier
            .padding(top = 4.dp, bottom = 28.dp)
            .onSizeChanged { size ->
                if (widgetWidth != size.width) {
                    widgetWidth = size.width;
                    updateAnchored()
                }
                if (widgetHeight != size.height) {
                    widgetHeight = size.height
                }
            }
            .customAnchoredDraggable(
                state = anchoredDraggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    dragStartX = anchoredDraggableState.value
                },
                onLimitDragEnded = { nextValue ->
                    val dis = (nextValue - dragStartX).absoluteValue.coerceIn(0f, widgetWidth.toFloat());
                    val sign = (nextValue - dragStartX).sign;
                    val next = dragStartX + dis * sign
                    next
                }
            )
            .fillMaxSize()
    ) {
        if (widgetWidth > 0) {
            if (canPrev) {
                Box(
                    modifier = Modifier
                        .offset(x = -widgetWidthDp + deltaDp),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverImage(dataSourceKey = prevCover)
                }
            }
            if (canNext) {
                Box(
                    modifier = Modifier
                        .offset(x = widgetWidthDp + deltaDp),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverImage(dataSourceKey = nextCover)
                }
            }
        }
        Box(
            modifier = Modifier
                .offset(x = deltaDp),
            contentAlignment = Alignment.Center,
        ) {
            if (!showLyric) {
                CoverImage(dataSourceKey = cover)
            } else {
                MusicLyric(
                    lyricIndex = lyricIndex,
                    lyrics = lyrics,
                    lyricLoadedState = lyricLoadedState,
                    emptyActionLabel = emptyActionLabel,
                    onClickEmptyAction = onClickLyricAction,
                    widgetHeight = widgetHeight,
                )
            }
        }
    }
}

@Composable
private fun TransportControls(
    modifier: Modifier = Modifier,
    playerVM: PlayerVM = hiltViewModel(),
) {
    val previousMusic by playerVM.previousMusic.collectAsState()
    val nextMusic by playerVM.nextMusic.collectAsState()
    val playing by playerVM.playing.collectAsState()
    val loading by playerVM.loading.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
    ) {
        EaseIconButton(
            sizeType = EaseIconButtonSize.Large,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_play_previous),
            disabled = previousMusic == null,
            onClick = {
                playerVM.playPrevious()
            }
        )
        if (!playing) {
            EaseIconButton(
                sizeType = EaseIconButtonSize.Large,
                buttonType = EaseIconButtonType.Primary,
                painter = painterResource(id = R.drawable.icon_play),
                disabled = loading,
                overrideColors = if (loading) {
                    EaseIconButtonColors(
                        buttonDisabledBg = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    null
                },
                onClick = {
                    playerVM.resume()
                }
            )
        } else {
            EaseIconButton(
                sizeType = EaseIconButtonSize.Large,
                buttonType = EaseIconButtonType.Primary,
                painter = painterResource(id = R.drawable.icon_pause),
                onClick = {
                    playerVM.pause()
                }
            )
        }
        EaseIconButton(
            sizeType = EaseIconButtonSize.Large,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_play_next),
            disabled = nextMusic == null,
            onClick = {
                playerVM.playNext()
            }
        )
    }
}

@Composable
private fun MusicPanel(
    hasLyric: Boolean,
    showLyric: Boolean,
    hasQueue: Boolean,
    onToggleLyric: () -> Unit,
    onDownload: () -> Unit,
    onOpenQueue: () -> Unit,
    playerVM: PlayerVM = hiltViewModel(),
    sleepModeVM: SleepModeVM = hiltViewModel()
) {
    val playMode by playerVM.playMode.collectAsState()
    val timeToPauseState by sleepModeVM.state.collectAsState()

    val isTimeToPauseOpen = timeToPauseState.enabled
    val modeDrawable = when (playMode) {
        PlayMode.SINGLE -> R.drawable.icon_mode_one
        PlayMode.SINGLE_LOOP -> R.drawable.icon_mode_repeatone
        PlayMode.LIST -> R.drawable.icon_mode_list
        PlayMode.LIST_LOOP -> R.drawable.icon_mode_repeat
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = if (isTimeToPauseOpen) {
                EaseIconButtonType.Primary
            } else {
                EaseIconButtonType.Default
            },
            overrideColors = EaseIconButtonColors(
                iconTint = if (isTimeToPauseOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                buttonBg = Color.Transparent,
            ),
            painter = painterResource(id = R.drawable.icon_timelapse),
            onClick = {
                sleepModeVM.openModal()
            }
        )
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = if (showLyric && hasLyric) {
                EaseIconButtonType.Primary
            } else {
                EaseIconButtonType.Default
            },
            painter = painterResource(id = R.drawable.icon_lyrics),
            disabled = !hasLyric,
            overrideColors = EaseIconButtonColors(
                iconTint = if (showLyric && hasLyric) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            onClick = onToggleLyric,
        )
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_download),
            onClick = {
                onDownload()
            }
        )
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = R.drawable.icon_mode_list),
            disabled = !hasQueue,
            onClick = onOpenQueue,
        )
        EaseIconButton(
            sizeType = EaseIconButtonSize.Medium,
            buttonType = EaseIconButtonType.Default,
            painter = painterResource(id = modeDrawable),
            onClick = {
                playerVM.changePlayModeToNext()
            }
        )
    }
}

@Composable
private fun currentQueueSubtitle(
    queue: PlaybackQueueSnapshot?,
    sourcePlaylist: Playlist?,
): String {
    return when {
        sourcePlaylist != null -> sourcePlaylist.abstr.meta.title
        queue?.context?.type == PlaybackContextType.FOLDER -> queue.context.folderPath ?: ""
        else -> stringResource(id = R.string.music_queue_sheet_temporary)
    }
}

@Composable
private fun ReorderableCollectionItemScope.PlaybackQueueRow(
    entry: PlaybackQueueEntry,
    isCurrent: Boolean,
    isDragging: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onCommitMove: () -> Unit,
) {
    val shape = RoundedCornerShape(EaseTheme.radius.card)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    EaseTheme.surfaces.secondary.copy(alpha = if (isDragging) 0.96f else EaseTheme.surfaces.secondary.alpha)
                }
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = EaseTheme.spacing.sm, vertical = EaseTheme.spacing.sm)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else EaseTheme.surfaces.card
                )
        ) {
            if (isCurrent) {
                Icon(
                    painter = painterResource(id = R.drawable.icon_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(12.dp)
                )
            } else {
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = EaseTheme.typography.sectionTitle,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = entry.musicAbstract.meta.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.cardTitle.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.musicAbstract.meta.duration?.let { formatDuration(it) } ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        EaseIconButton(
            sizeType = EaseIconButtonSize.Small,
            buttonType = EaseIconButtonType.Error,
            painter = painterResource(id = R.drawable.icon_deleteseep),
            onClick = onRemove,
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .draggableHandle(
                    onDragStopped = {
                        onCommitMove()
                    }
                )
        ) {
            Icon(
                painter = painterResource(id = R.drawable.icon_drag),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackQueueSheet(
    queue: PlaybackQueueSnapshot,
    currentQueueEntryId: String?,
    sourcePlaylist: Playlist?,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
) {
    val baseOrder = queue.entries.map { it.queueEntryId }
    var draftEntries by remember(baseOrder) {
        mutableStateOf(queue.entries)
    }
    val currentOrder by rememberUpdatedState(queue.entries.map { it.queueEntryId })
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
        if (
            from.index == to.index ||
            from.index !in draftEntries.indices ||
            to.index !in draftEntries.indices
        ) {
            return@rememberReorderableLazyListState
        }
        draftEntries = draftEntries.toMutableList().apply {
            val moved = removeAt(from.index)
            add(to.index, moved)
        }
    }

    fun commitDraftOrder() {
        val draftOrder = draftEntries.map { it.queueEntryId }
        if (draftOrder != currentOrder) {
            onCommitOrder(draftOrder)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EaseTheme.surfaces.dialog,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = stringResource(id = R.string.music_queue_sheet_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = EaseTheme.typography.sectionTitle.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = currentQueueSubtitle(queue = queue, sourcePlaylist = sourcePlaylist),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = EaseTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            items(draftEntries, key = { entry -> entry.queueEntryId }) { entry ->
                ReorderableItem(reorderState, key = entry.queueEntryId) { isDragging ->
                    PlaybackQueueRow(
                        entry = entry,
                        isCurrent = entry.queueEntryId == currentQueueEntryId,
                        isDragging = isDragging,
                        onPlay = { onPlay(entry.queueEntryId) },
                        onRemove = { onRemove(entry.queueEntryId) },
                        onCommitMove = ::commitDraftOrder,
                    )
                }
            }
            item {
                Box(modifier = Modifier.height(18.dp))
            }
        }
    }
}

@Composable
fun MusicPlayerPage(
    playerVM: PlayerVM = hiltViewModel(),
    lrcApiVM: LrcApiVM = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val currentMusic by playerVM.music.collectAsState()
    val sourcePlaylist by playerVM.playlist.collectAsState()
    val playbackQueue by playerVM.playbackQueue.collectAsState()
    val currentQueueEntryId by playerVM.currentQueueEntryId.collectAsState()
    val currentDuration by playerVM.currentDuration.collectAsState()
    val previousMusic by playerVM.previousMusic.collectAsState()
    val nextMusic by playerVM.nextMusic.collectAsState()
    val lrcApiSettings by lrcApiVM.settings.collectAsState()
    val remoteLyricsState by lrcApiVM.currentLyricsState.collectAsState()
    val bufferDuration by playerVM.bufferDuration.collectAsState()
    val displayTotalDuration by playerVM.displayTotalDuration.collectAsState()
    val localLyricState = currentMusic?.lyric?.loadedState
    val activeRemoteLyricsState = if (remoteLyricsState.musicId == currentMusic?.meta?.id) {
        remoteLyricsState
    } else {
        null
    }
    val lyricLoadedState = when {
        localLyricState == LyricLoadState.LOADED -> LyricLoadState.LOADED
        activeRemoteLyricsState?.state == LyricLoadState.LOADED -> LyricLoadState.LOADED
        localLyricState != null -> localLyricState
        activeRemoteLyricsState != null -> activeRemoteLyricsState.state
        else -> LyricLoadState.MISSING
    }
    val displayLyrics = when {
        localLyricState == LyricLoadState.LOADED -> currentMusic?.lyric?.data?.lines ?: emptyList()
        activeRemoteLyricsState?.state == LyricLoadState.LOADED -> activeRemoteLyricsState.lyrics?.lines
            ?: emptyList()
        else -> emptyList()
    }
    val currentLyricIndex = resolveLyricIndex(currentDuration, displayLyrics)
    var showLyric by rememberSaveable { mutableStateOf(false) }
    var queueSheetOpen by rememberSaveable { mutableStateOf(false) }

    val hasLyric = lyricLoadedState == LyricLoadState.LOADED
    val hasQueue = playbackQueue?.entries?.isNotEmpty() == true
    val hasRemovableLyric = currentMusic?.lyric?.loadedState != null &&
        currentMusic?.lyric?.loadedState != LyricLoadState.MISSING
    val lrcApiReady = lrcApiSettings.isReadyForFetch()
    val lyricActionLabel = stringResource(
        id = if (lrcApiReady) {
            R.string.music_lyric_retry_fetch
        } else {
            R.string.music_lyric_configure_lrcapi
        }
    )
    val onLyricAction = {
        if (lrcApiReady) {
            lrcApiVM.retryCurrentMusic()
        } else {
            navController.navigate(RouteLrcApiSettings())
        }
    }

    LaunchedEffect(currentMusic?.meta?.id) {
        showLyric = false
    }

    LaunchedEffect(hasLyric) {
        if (!hasLyric && showLyric) {
            showLyric = false
        }
    }
    LaunchedEffect(hasQueue) {
        if (!hasQueue && queueSheetOpen) {
            queueSheetOpen = false
        }
    }

    Box(
        modifier = Modifier
            .clipToBounds()
            .background(EaseTheme.surfaces.screen)
            .fillMaxSize()
    ) {
        Column {
            MusicPlayerHeader(
                hasRemovableLyric = hasRemovableLyric,
                lrcApiReady = lrcApiReady,
                onRetryLrcApiLyric = onLyricAction,
                onOpenLrcApiSettings = {
                    navController.navigate(RouteLrcApiSettings())
                },
            )
            Column(
                modifier = Modifier
                    .weight(1.0F)
            ) {
                MusicPlayerBody(
                    onPrev = {
                        playerVM.playPrevious()
                    },
                    onNext = {
                        playerVM.playNext()
                    },
                    cover = currentMusic?.cover,
                    prevCover = previousMusic?.cover,
                    nextCover = nextMusic?.cover,
                    canPrev = previousMusic != null,
                    canNext = nextMusic != null,
                    showLyric = showLyric,
                    lyricIndex = currentLyricIndex,
                    lyricLoadedState = lyricLoadedState,
                    lyrics = displayLyrics,
                    emptyActionLabel = lyricActionLabel,
                    onClickLyricAction = onLyricAction,
                    onResetLyric = { showLyric = false }
                )
            }
            Column(
                modifier = Modifier.padding(
                    start = EaseTheme.spacing.hero,
                    end = EaseTheme.spacing.hero,
                    top = EaseTheme.spacing.xxs / 2,
                    bottom = EaseTheme.spacing.xl,
                )
            ) {
                Text(
                    text = currentMusic?.meta?.title ?: "",
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = EaseTheme.typography.sectionTitle,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                MusicSlider(
                    currentDuration = formatDuration(currentDuration),
                    _currentDurationMS = toMusicDurationMs(currentDuration),
                    bufferDurationMS = bufferDuration.toMillis().toULong(),
                    totalDuration = formatDuration(displayTotalDuration),
                    totalDurationMS = toMusicDurationMs(displayTotalDuration),
                    onChangeMusicPosition = { nextMS ->
                        playerVM.seek(nextMS)
                    }
                )
                Box(modifier = Modifier.height(22.dp))
                TransportControls(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Box(modifier = Modifier.height(18.dp))
                MusicPanel(
                    hasLyric = hasLyric,
                    showLyric = showLyric,
                    hasQueue = hasQueue,
                    onToggleLyric = {
                        if (hasLyric) {
                            showLyric = !showLyric
                        }
                    },
                    onDownload = {
                        playerVM.downloadCurrent()
                    },
                    onOpenQueue = {
                        if (hasQueue) {
                            queueSheetOpen = true
                        }
                    }
                )
            }
        }
        if (queueSheetOpen && playbackQueue != null) {
            PlaybackQueueSheet(
                queue = playbackQueue!!,
                currentQueueEntryId = currentQueueEntryId,
                sourcePlaylist = sourcePlaylist,
                onDismiss = { queueSheetOpen = false },
                onPlay = { queueEntryId ->
                    queueSheetOpen = false
                    playerVM.playQueueEntry(queueEntryId)
                },
                onRemove = { queueEntryId ->
                    playerVM.removeQueueEntry(queueEntryId)
                },
                onCommitOrder = { orderedQueueEntryIds ->
                    playerVM.commitQueueOrder(orderedQueueEntryIds)
                },
            )
        }
    }
}

@Preview(
    widthDp = 400,
    heightDp = 400,
)
@Composable
private fun MusicSliderPreview() {
    fun formatMS(ms: ULong): String {
        var seconds = ms / 1000u
        val minutes = seconds / 60u
        seconds %= 60u

        val m = minutes.toString().padStart(2, '0')
        val s = seconds.toString().padStart(2, '0')

        return "${m}:${s}"
    }

    val totalMS = 120uL * 1000uL
    var currentMS by remember {
        mutableStateOf(60uL * 1000uL)
    }
    val bufferMS by remember {
        mutableStateOf(70uL * 1000uL)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        MusicSlider(
            currentDuration = formatMS(currentMS),
            _currentDurationMS = currentMS,
            bufferDurationMS = bufferMS,
            totalDuration = formatMS(totalMS),
            totalDurationMS = totalMS,
            onChangeMusicPosition = { nextMS ->
                currentMS = nextMS
            }
        )
    }
}


@Preview(
    widthDp = 400,
    heightDp = 800,
)
@Composable
private fun MusicPlayerBodyPreview() {
    var canPrev by remember { mutableStateOf(true) }
    var canNext by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Row {
            Column {
                Text(text = "canPrev")
                Switch(
                    checked = canPrev,
                    onCheckedChange = { value -> canPrev = value }
                )
            }
            Column {
                Text(text = "canNext")
                Switch(
                    checked = canNext,
                    onCheckedChange = { value -> canNext = value }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Color.Blue)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f)
        ) {
            MusicPlayerBody(
                onPrev = {
                },
                onNext = {
                },
                cover = null,
                prevCover = null,
                nextCover = null,
                canPrev = canPrev,
                canNext = canNext,
                showLyric = false,
                lyricIndex = 0,
                lyrics = listOf(),
                lyricLoadedState = LyricLoadState.LOADING,
                emptyActionLabel = "Retry",
                onClickLyricAction = {},
                onResetLyric = {}
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Color.Blue)
        )
    }
}


@Preview()
@Composable
private fun MusicLyricPreview() {
    var lyricLoadedState by remember { mutableStateOf(LyricLoadState.LOADED) }
    var lyricIndex by remember { mutableIntStateOf(0) }
    val lyricLines = remember {
        listOf(
            LyricLine(Duration.ofMillis(1000), "> Task :app:preBuild UP-TO-DATE"),
            LyricLine(Duration.ofMillis(3000), "> Task :app:preDebugBuild UP-TO-DATE"),
            LyricLine(Duration.ofMillis(4000), "> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE"),
            LyricLine(Duration.ofMillis(4500), "> Task :app:checkDebugAarMetadata UP-TO-DATE"),
            LyricLine(Duration.ofMillis(5000), "> Task :app:generateDebugResValues UP-TO-DATE"),
            LyricLine(
                Duration.ofMillis(5500),
                "For more on this, please refer to https://docs.gradle.org/8.9/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation."
            ),
            LyricLine(Duration.ofMillis(6000), "> Task :app:generateDebugResValues UP-TO-DATE"),
            LyricLine(
                Duration.ofMillis(7000),
                "You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins."
            ),
            LyricLine(Duration.ofMillis(8000), "> Task :app:createDebugApkListingFileRedirect UP-TO-DATE"),
            LyricLine(Duration.ofMillis(9000), "> Task :app:assembleDebug"),
        )
    }
    var widgetHeight by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .width(400.dp)
            .height(600.dp)
    ) {
        Row {
            Button(
                onClick = {
                    if (lyricIndex > 0) {
                        lyricIndex -= 1
                    }
                }
            ) {
                Text(text = "-")
            }
            Button(
                onClick = {
                    if (lyricIndex < lyricLines.size - 1) {
                        lyricIndex += 1
                    }
                }
            ) {
                Text(text = "+")
            }
        }
        Row {
            Button(onClick = { lyricLoadedState = LyricLoadState.MISSING }) { Text("MISSING") }
            Button(onClick = { lyricLoadedState = LyricLoadState.LOADING }) { Text("LOADING") }
            Button(onClick = { lyricLoadedState = LyricLoadState.LOADED }) { Text("LOADED") }
            Button(onClick = { lyricLoadedState = LyricLoadState.FAILED }) { Text("FAILED") }
        }
        Box(
            modifier = Modifier
            .onSizeChanged { size ->
                if (size.height != widgetHeight) {
                    widgetHeight = size.height
                }
            }
            .fillMaxSize()
        ) {
            MusicLyric(
                lyricIndex = lyricIndex,
                lyrics = lyricLines,
                lyricLoadedState = lyricLoadedState,
                emptyActionLabel = "Retry",
                widgetHeight = widgetHeight,
                onClickEmptyAction = {}
            )
        }
    }
}
