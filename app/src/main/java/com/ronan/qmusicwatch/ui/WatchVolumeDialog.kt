package com.ronan.qmusicwatch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ronan.qmusicwatch.input.RotaryInputKind
import com.ronan.qmusicwatch.input.RotaryVolumeAccumulator
import com.ronan.qmusicwatch.playback.DeviceVolumeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Relative steps only: never derive an absolute volume from a stale percentage. */
internal fun canStepDeviceVolume(volume: DeviceVolumeState?, direction: Int): Boolean =
    volume != null && volume.max > 0 && when {
        direction > 0 -> volume.current < volume.max
        direction < 0 -> volume.current > 0
        else -> false
    }

@Composable
internal fun WatchVolumeDialog(
    volume: DeviceVolumeState?,
    rotaryEnabled: Boolean,
    onRefresh: () -> Unit,
    onAdjustVolume: (Int) -> Boolean,
    onDismiss: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Also reflect changes made on the Bluetooth headset or system UI.
            // Stop polling as soon as this screen closes or the app is paused.
            while (isActive) {
                refresh()
                delay(500)
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WatchRotaryDialogContent {
            val reversed = LocalWatchRotaryReversed.current
            val accumulator = remember(rotaryEnabled, reversed) { RotaryVolumeAccumulator() }
            val threshold = with(LocalDensity.current) { 16.dp.toPx() }
            WatchRotaryHandler { event ->
                if (rotaryEnabled) {
                    val pixels = event.pixels * if (reversed && event.kind != RotaryInputKind.VolumeKey) -1f else 1f
                    accumulator.step(event.copy(pixels = pixels), threshold)?.let { direction ->
                        if (canStepDeviceVolume(volume, direction)) onAdjustVolume(direction)
                    }
                }
                // Modal owns these events, including when crown volume is off.
                // They must not leak through and adjust the underlying player.
                true
            }
            WatchVolumeContent(volume, onAdjustVolume, onDismiss)
        }
    }
}

/** Full-screen, centred controls keep all touch targets inside the round chord. */
@Composable
internal fun WatchVolumeContent(
    volume: DeviceVolumeState?,
    onAdjustVolume: (Int) -> Boolean,
    onDismiss: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    val dismiss by rememberUpdatedState(onDismiss)
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val fraction = volume?.takeIf { it.max > 0 }
        ?.let { it.current.toFloat() / it.max }?.coerceIn(0f, 1f) ?: 0f
    val animatedFraction by animateFloatAsState(
        fraction,
        tween(160, easing = FastOutSlowInEasing),
        label = "touchVolumeProgress",
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(swipeThreshold) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onHorizontalDrag = { change, amount ->
                        totalDx += amount
                        // Cancel any pressed volume button once a drag wins.
                        change.consume()
                    },
                    onDragEnd = {
                        if (totalDx >= swipeThreshold) dismiss()
                        totalDx = 0f
                    },
                    onDragCancel = { totalDx = 0f },
                )
            },
    ) {
        Text(
            "音量",
            Modifier.align(Alignment.TopCenter).padding(top = if (dimensions.isRound) maxHeight * .075f else 12.dp),
            color = WatchTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        WatchIconButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            "返回",
            Modifier.align(Alignment.TopStart)
                .padding(
                    start = if (dimensions.isRound) maxWidth * .16f else 6.dp,
                    top = if (dimensions.isRound) maxHeight * .15f else 5.dp,
                ).size(30.dp),
            containerColor = Color.Transparent,
            onClick = onDismiss,
        )
        Column(
            Modifier.align(Alignment.Center).width(minOf(maxWidth * .72f, 184.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                when {
                    volume?.muted == true || volume?.percent == 0 -> Icons.Default.VolumeOff
                    volume?.percent?.let { it < 50 } == true -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                null,
                Modifier.size(22.dp),
                tint = WatchTextPrimary,
            )
            AnimatedContent(
                targetState = volume?.percent,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
                label = "touchVolumePercent",
            ) { percent ->
                Text(
                    percent?.let { "$it%" } ?: "—",
                    color = WatchTextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.width(112.dp).height(3.dp),
                color = WatchTextPrimary,
                trackColor = WatchDivider,
            )
            Spacer(Modifier.height(2.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = .08f),
                tonalElevation = 0.dp,
            ) {
                Row(Modifier.width(160.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(-1, 1).forEach { direction ->
                        if (direction == 1) Box(Modifier.width(1.dp).height(18.dp).background(WatchDivider))
                        val enabled = canStepDeviceVolume(volume, direction)
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable(enabled = enabled, role = Role.Button) { onAdjustVolume(direction) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (direction < 0) Icons.Default.Remove else Icons.Default.Add,
                                if (direction < 0) "减小音量" else "增大音量",
                                Modifier.size(24.dp),
                                tint = WatchTextPrimary.copy(alpha = if (enabled) 1f else .28f),
                            )
                        }
                    }
                }
            }
        }
    }
}
