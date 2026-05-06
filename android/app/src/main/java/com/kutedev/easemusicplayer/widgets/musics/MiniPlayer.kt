package com.kutedev.easemusicplayer.widgets.musics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.components.MusicCover
import com.kutedev.easemusicplayer.viewmodels.PlayerVM
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteMusicPlayer
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.kutedev.easemusicplayer.utils.toMusicDurationMs
import uniffi.ease_client_schema.DataSourceKey
import kotlinx.coroutines.delay

@Composable
internal fun MiniPlayerSeekBar(
    currentDurationMS: ULong,
    totalDurationMS: ULong?,
    onSeek: (ULong) -> Unit,
) {
    val maxValue = totalDurationMS?.toFloat()?.takeIf { it > 0f } ?: 1f
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var optimisticPositionMs by remember(totalDurationMS) { mutableStateOf(null as ULong?) }

    LaunchedEffect(currentDurationMS, totalDurationMS, dragging, optimisticPositionMs) {
        if (dragging) {
            return@LaunchedEffect
        }
        if (currentDurationMS == 0uL) {
            optimisticPositionMs = null
        }
        val optimistic = optimisticPositionMs
        if (optimistic != null && !shouldClearOptimisticSeek(currentDurationMS, optimistic)) {
            sliderValue = optimistic
                .toFloat()
                .coerceIn(0f, maxValue)
            return@LaunchedEffect
        }
        optimisticPositionMs = null
        sliderValue = currentDurationMS
            .coerceAtMost(totalDurationMS ?: 0uL)
            .toFloat()
            .coerceIn(0f, maxValue)
    }

    LaunchedEffect(optimisticPositionMs, dragging) {
        if (dragging) {
            return@LaunchedEffect
        }
        val optimistic = optimisticPositionMs ?: return@LaunchedEffect
        delay(OPTIMISTIC_SEEK_TIMEOUT_MS)
        if (!dragging && optimisticPositionMs == optimistic) {
            optimisticPositionMs = null
        }
    }

    val progress = resolveSeekRatio(
        positionMs = optimisticPositionMs ?: currentDurationMS,
        totalDurationMs = totalDurationMS,
    )

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .fillMaxWidth()
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = { progress },
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = EaseTheme.surfaces.secondary,
            )
        }
        Slider(
            value = sliderValue.coerceIn(0f, maxValue),
            onValueChange = { nextValue ->
                if (totalDurationMS?.takeIf { it > 0uL } == null) {
                    return@Slider
                }
                dragging = true
                val resolvedPositionMs = resolveSeekPositionFromSliderValue(
                    value = nextValue,
                    totalDurationMs = totalDurationMS,
                )
                optimisticPositionMs = resolvedPositionMs
                sliderValue = resolvedPositionMs
                    .toFloat()
                    .coerceIn(0f, maxValue)
            },
            onValueChangeFinished = {
                if (totalDurationMS?.takeIf { it > 0uL } == null) {
                    dragging = false
                    optimisticPositionMs = null
                    return@Slider
                }
                val resolvedPositionMs = optimisticPositionMs
                    ?: resolveSeekPositionFromSliderValue(
                        value = sliderValue,
                        totalDurationMs = totalDurationMS,
                    )
                onSeek(resolvedPositionMs)
                dragging = false
            },
            valueRange = 0f..maxValue,
            enabled = totalDurationMS?.takeIf { it > 0uL } != null,
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MINI_PLAYER_SEEK_BAR_TEST_TAG),
        )
    }
}

@Composable
private fun MiniPlayerCore(
    isPlaying: Boolean,
    title: String,
    cover: DataSourceKey?,
    currentDurationMS: ULong,
    totalDuration: String,
    totalDurationMS: ULong?,
    loading: Boolean,
    canNext: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onSeek: (ULong) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EaseTheme.spacing.hero, vertical = EaseTheme.spacing.lg)
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(EaseTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            MusicCover(
                modifier = Modifier
                    .clip(RoundedCornerShape(EaseTheme.radius.sm))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(EaseTheme.radius.sm),
                    )
                    .size(60.dp),
                coverDataSourceKey = cover,
            )
            Box(modifier = Modifier.width(EaseTheme.spacing.md))
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = EaseTheme.typography.cardTitle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1
                )
                MiniPlayerSeekBar(
                    currentDurationMS = currentDurationMS,
                    totalDurationMS = totalDurationMS,
                    onSeek = onSeek,
                )
                Text(
                    text = totalDuration,
                    style = EaseTheme.typography.micro,
                )
            }
        }
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(EaseTheme.surfaces.secondary)
                .padding(horizontal = EaseTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isPlaying) {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Primary,
                    disabled = loading,
                    painter = painterResource(R.drawable.icon_play),
                    onClick = onPlay,
                )
            } else {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Medium,
                    buttonType = EaseIconButtonType.Primary,
                    painter = painterResource(R.drawable.icon_pause),
                    onClick = onPause,
                )
            }
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = EaseIconButtonType.Default,
                painter = painterResource(R.drawable.icon_play_next),
                disabled = !canNext,
                onClick = onNext,
            )
            EaseIconButton(
                sizeType = EaseIconButtonSize.Medium,
                buttonType = EaseIconButtonType.Error,
                painter = painterResource(R.drawable.icon_stop),
                onClick = onStop,
            )
        }
    }
}

@Composable
fun MiniPlayer(
    playerVM: PlayerVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val isPlaying by playerVM.playing.collectAsState()
    val music by playerVM.music.collectAsState()
    val loading by playerVM.loading.collectAsState()
    val nextMusic by playerVM.nextMusic.collectAsState()
    val currentDuration by playerVM.currentDuration.collectAsState()

    MiniPlayerCore(
        isPlaying = isPlaying,
        title = music?.meta?.title ?: "",
        cover = music?.cover,
        currentDurationMS = toMusicDurationMs(currentDuration),
        totalDuration = formatProgressDuration(music?.meta?.duration?.toMillis()?.toULong()),
        totalDurationMS = music?.meta?.duration?.toMillis()?.toULong(),
        canNext = nextMusic != null,
        loading = loading,
        onClick = { navController.navigate(RouteMusicPlayer()) },
        onPlay = { playerVM.resume() },
        onPause = { playerVM.pause() },
        onStop = { playerVM.stop() },
        onNext = { playerVM.playNext() },
        onSeek = { playerVM.seek(it) },
    )
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    MiniPlayerCore(
        isPlaying = true,
        title = "Very very very very very long music title",
        cover = null,
        currentDurationMS = 10uL,
        totalDuration = "00:06",
        totalDurationMS = 60uL,
        canNext = false,
        loading = false,
        onClick = {},
        onPlay = {},
        onPause = {},
        onStop = {},
        onNext = {},
        onSeek = {},
    )
}
