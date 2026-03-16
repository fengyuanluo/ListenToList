package com.kutedev.easemusicplayer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class EaseSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val hero: Dp = 36.dp,
) {
    val page: Dp get() = xl
    val content: Dp get() = lg
    val cardPadding: Dp get() = md
    val dialogPadding: Dp get() = xl
    val sectionGap: Dp get() = xl
}

@Immutable
data class EaseRadius(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val full: Dp = 999.dp,
) {
    val compact: Dp get() = md
    val card: Dp get() = lg
    val hero: Dp get() = xl
    val control: Dp get() = full
}

val AppEaseSpacing = EaseSpacing()
val AppEaseRadius = EaseRadius()
