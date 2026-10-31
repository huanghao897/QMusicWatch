package com.ronan.qmusicwatch.ui

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ronan.qmusicwatch.input.RotaryInputKind
import com.ronan.qmusicwatch.input.WatchRotaryDispatcher
import com.ronan.qmusicwatch.input.WatchRotaryEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalWatchRotaryDispatcher = staticCompositionLocalOf<WatchRotaryDispatcher?> { null }
internal val LocalWatchRotaryPriority = staticCompositionLocalOf { 0 }
internal val LocalWatchRotaryReversed = staticCompositionLocalOf { false }
internal val LocalVolumeKeysScroll = staticCompositionLocalOf { false }

@Composable
internal fun WatchRotaryDialogContent(content: @Composable () -> Unit) {
    val priority = LocalWatchRotaryPriority.current + 100
    WatchRotaryDialogWindow()
    CompositionLocalProvider(LocalWatchRotaryPriority provides priority) {
        // A non-scrolling modal still blocks rotation from reaching the player.
        WatchRotaryHandler { it.kind != RotaryInputKind.VolumeKey }
        CompositionLocalProvider(LocalWatchRotaryPriority provides priority + 1, content = content)
    }
}

@Composable
internal fun WatchRotaryHandler(enabled: Boolean = true, onEvent: (WatchRotaryEvent) -> Boolean) {
    val dispatcher = LocalWatchRotaryDispatcher.current
    val priority = LocalWatchRotaryPriority.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val action by rememberUpdatedState(onEvent)
    DisposableEffect(dispatcher, lifecycle, enabled, priority) {
        var unregister: (() -> Unit)? = null
        fun update() {
            if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (unregister == null) unregister = dispatcher?.register(priority) { action(it) }
            } else {
                unregister?.invoke()
                unregister = null
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); unregister?.invoke() }
    }
}

/** Dialogs have a different Window, so their OEM input must use the same router. */
@Composable
internal fun WatchRotaryDialogWindow() {
    val dispatcher = LocalWatchRotaryDispatcher.current
    val view = LocalView.current
    DisposableEffect(view, dispatcher) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window == null || original == null || dispatcher == null) {
            onDispose { }
        } else {
            val wrapper = object : Window.Callback by original {
                override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                    dispatcher.dispatchMotion(event) || original.dispatchGenericMotionEvent(event)

                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    dispatcher.dispatchKey(event, window.rotaryTextInputActive()) || original.dispatchKeyEvent(event)
            }
            window.callback = wrapper
            onDispose { if (window.callback === wrapper) window.callback = original }
        }
    }
}

internal fun Window.rotaryTextInputActive(): Boolean =
    currentFocus?.onCheckIsTextEditor() == true ||
        ViewCompat.getRootWindowInsets(decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** A bounded, interruptible scroll; quick turns never build an animation backlog. */
@Composable
internal fun rememberWatchRotaryScroll(state: ScrollableState): (Float) -> Unit {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxPixels = with(density) { 180.dp.toPx() }
    return remember(state, scope, maxPixels) {
        var job: Job? = null
        var pending = 0f
        var generation = 0
        val onScroll: (Float) -> Unit = { pixels ->
            if (pixels.isFinite() && pixels != 0f) {
                val currentGeneration = ++generation
                job?.cancel()
                if (pending * pixels < 0f) pending = 0f
                pending = (pending + pixels).coerceIn(-maxPixels, maxPixels)
                job = scope.launch {
                    val target = pending
                    try {
                        state.scroll {
                            var previous = 0f
                            animate(0f, target, animationSpec = tween(90, easing = LinearOutSlowInEasing)) { value, _ ->
                                pending -= scrollBy(value - previous)
                                previous = value
                            }
                        }
                    } finally {
                        // A newer sample owns any remaining motion. Touch input
                        // cancelling this job, or reaching a list edge, clears it.
                        if (currentGeneration == generation) pending = 0f
                    }
                }
            }
        }
        onScroll
    }
}

@Composable
internal fun WatchLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    rotaryEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val reversed = LocalWatchRotaryReversed.current
    val volumeKeysScroll = LocalVolumeKeysScroll.current
    val scroll = rememberWatchRotaryScroll(state)
    WatchRotaryHandler(rotaryEnabled) { event ->
        if (event.kind == RotaryInputKind.VolumeKey && !volumeKeysScroll) false else {
            scroll(event.pixels * if (reversed) -1f else 1f)
            true
        }
    }
    LazyColumn(modifier, state, contentPadding, verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment, content = content)
}
