package com.kutedev.easemusicplayer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kutedev.easemusicplayer.ui.theme.EaseTheme
import com.moriafly.salt.ui.SaltTheme

@Composable
fun EaseSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    height: Dp = 20.dp,
    trackColor: Color = EaseTheme.surfaces.secondary,
    activeTrackColor: Color = SaltTheme.colors.highlight,
    thumbColor: Color = SaltTheme.colors.highlight,
    thumbSize: Dp = 18.dp,
    onValueChangeFinished: (() -> Unit)? = null,
    drawTrack: (@Composable BoxScope.(progress: Float) -> Unit)? = null,
) {
    val safeStart = valueRange.start
    val safeEnd = valueRange.endInclusive
    val rangeSpan = (safeEnd - safeStart).takeIf { it > 0f } ?: 1f
    val clampedValue = value.coerceIn(safeStart, safeEnd)
    val progress = ((clampedValue - safeStart) / rangeSpan).coerceIn(0f, 1f)
    var widthPx by remember { mutableIntStateOf(0) }
    val draggableState = rememberDraggableState { delta ->
        if (!enabled || widthPx <= 0) {
            return@rememberDraggableState
        }
        val next = clampedValue + (delta / widthPx.toFloat()) * rangeSpan
        onValueChange(next.coerceIn(safeStart, safeEnd))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clampedValue, safeStart..safeEnd, 0)
                setProgress { targetValue ->
                    if (!enabled) {
                        return@setProgress false
                    }
                    onValueChange(targetValue.coerceIn(safeStart, safeEnd))
                    onValueChangeFinished?.invoke()
                    true
                }
            }
            .onSizeChanged { size ->
                widthPx = size.width
            }
            .pointerInput(enabled, widthPx, safeStart, safeEnd) {
                detectTapGestures(
                    onTap = { offset ->
                        if (!enabled || widthPx <= 0) {
                            return@detectTapGestures
                        }
                        val tappedProgress = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        onValueChange((safeStart + tappedProgress * rangeSpan).coerceIn(safeStart, safeEnd))
                        onValueChangeFinished?.invoke()
                    }
                )
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { onValueChangeFinished?.invoke() },
            )
    ) {
        val thumbOffset = (maxWidth - thumbSize) * progress
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(EaseTheme.radius.control))
                .background(trackColor)
        ) {
            drawTrack?.invoke(this, progress)
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .align(Alignment.CenterStart)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (enabled) thumbColor else thumbColor.copy(alpha = 0.45f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(EaseTheme.radius.control))
                    .background(activeTrackColor)
            )
        }
    }
}
