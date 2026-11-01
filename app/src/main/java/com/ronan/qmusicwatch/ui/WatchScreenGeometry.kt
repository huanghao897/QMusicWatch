package com.ronan.qmusicwatch.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Immutable
internal data class WatchScreenBounds(val width: Dp, val height: Dp)

internal val LocalWatchScreenBounds = staticCompositionLocalOf { WatchScreenBounds(240.dp, 240.dp) }

/** Width of a centred rectangle whose entire height stays inside the display circle. */
internal fun roundWatchContentWidth(
    screen: WatchScreenBounds,
    top: Dp,
    bottom: Dp,
    edgeMargin: Dp = 3.dp,
): Dp {
    val radius = min(screen.width.value, screen.height.value) / 2f
    val centreY = screen.height.value / 2f
    val distance = max(abs(top.value - centreY), abs(bottom.value - centreY))
    val halfChord = sqrt((radius * radius - distance * distance).coerceAtLeast(0f))
    return (2f * (halfChord - edgeMargin.value).coerceAtLeast(0f)).dp
}
