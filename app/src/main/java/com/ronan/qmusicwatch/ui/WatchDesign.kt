package com.ronan.qmusicwatch.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WatchBackground = Color(0xFF050505)
val WatchSurface = Color(0xFF151616)
val WatchSurfaceRaised = Color(0xFF252626)
val WatchTextPrimary = Color(0xFFF4F5F4)
val WatchTextSecondary = Color(0x99F4F5F4)
val WatchAccent = Color(0xFF78C7FF)
val WatchLike = Color(0xFFFF6B8B)
val WatchVip = WatchTextPrimary
val WatchDivider = Color(0xFF383A39)

enum class WatchUiSize(val storedValue: String) {
    Compact("compact"),
    Standard("standard"),
    Large("large");

    companion object {
        fun fromStored(value: String?): WatchUiSize =
            entries.firstOrNull { it.storedValue == value } ?: Compact
    }
}

enum class WatchWindowClass {
    Compact,
    Medium,
    Expanded,
}

@Immutable
data class WatchDimensions(
    val windowClass: WatchWindowClass,
    val uiSize: WatchUiSize,
    val isRound: Boolean,
    val screenPadding: Dp,
    val topSafeInset: Dp,
    val verticalPadding: Dp,
    val itemSpacing: Dp,
    val titleSp: Float,
    val bodySp: Float,
    val secondarySp: Float,
    val trackRowHeight: Dp,
    val searchHeight: Dp,
    val artworkSize: Dp,
    val miniPlayerHeight: Dp,
    val miniPlayerWidthFraction: Float,
    val touchTarget: Dp,
    val iconSize: Dp,
    val cornerRadius: Dp,
    val rowCornerRadius: Dp,
    val searchCornerRadius: Dp,
    val controlCornerRadius: Dp,
    val playerActionSize: Dp,
    val lyricRowHeight: Dp,
    val playerArtworkSize: Dp,
)

internal fun resolveWatchDimensions(
    width: Dp,
    uiSize: WatchUiSize,
    isRound: Boolean = false,
): WatchDimensions {
    val windowClass = when {
        width <= 280.dp -> WatchWindowClass.Compact
        width <= 360.dp -> WatchWindowClass.Medium
        else -> WatchWindowClass.Expanded
    }
    val scale = when (uiSize) {
        WatchUiSize.Compact -> 1f
        WatchUiSize.Standard -> 1.1f
        WatchUiSize.Large -> 1.22f
    }
    fun Dp.scaled() = this * scale
    return WatchDimensions(
        windowClass = windowClass,
        uiSize = uiSize,
        isRound = isRound,
        screenPadding = (if (isRound) 14.dp else 8.dp).scaled(),
        topSafeInset = (if (isRound) 12.dp else 8.dp).scaled(),
        verticalPadding = 5.dp.scaled(),
        itemSpacing = 4.dp.scaled(),
        titleSp = 18f * scale,
        bodySp = 13f * scale,
        secondarySp = 10.5f * scale,
        trackRowHeight = 46.dp.scaled(),
        searchHeight = 40.dp.scaled(),
        artworkSize = 36.dp.scaled(),
        miniPlayerHeight = (if (isRound) 58.dp else 50.dp).scaled(),
        miniPlayerWidthFraction = if (isRound) .82f else 1f,
        touchTarget = 40.dp.scaled(),
        iconSize = 19.dp.scaled(),
        cornerRadius = 23.dp.scaled(),
        rowCornerRadius = 23.dp.scaled(),
        searchCornerRadius = 20.dp.scaled(),
        controlCornerRadius = 20.dp.scaled(),
        playerActionSize = 36.dp.scaled(),
        lyricRowHeight = 36.dp.scaled(),
        playerArtworkSize = when (windowClass) {
            WatchWindowClass.Compact -> 76.dp
            WatchWindowClass.Medium -> 96.dp
            WatchWindowClass.Expanded -> 108.dp
        } * scale,
    )
}

val LocalWatchDimensions = staticCompositionLocalOf {
    resolveWatchDimensions(240.dp, WatchUiSize.Compact)
}

@Composable
fun QMusicWatchTheme(
    uiSize: String,
    pureBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val selectedSize = WatchUiSize.fromStored(uiSize)
    val isRound = LocalConfiguration.current.isScreenRound
    val colors = darkColorScheme(
        primary = WatchAccent,
        secondary = WatchLike,
        background = if (pureBlack) Color.Black else WatchBackground,
        surface = WatchSurface,
        surfaceVariant = WatchSurfaceRaised,
        onPrimary = Color(0xFF06131B),
        onBackground = WatchTextPrimary,
        onSurface = WatchTextPrimary,
        onSurfaceVariant = WatchTextSecondary,
        outline = WatchDivider,
        error = Color(0xFFFF7B7B),
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 13.sp),
            bodyMedium = TextStyle(fontSize = 12.sp),
            labelLarge = TextStyle(fontSize = 12.sp),
            titleLarge = TextStyle(fontSize = 18.sp),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalWatchDimensions provides resolveWatchDimensions(maxWidth, selectedSize, isRound),
                content = content,
            )
        }
    }
}
