package com.ronan.qmusicwatch.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.playback.hardwareVolumeDirection
import com.ronan.qmusicwatch.playback.rotaryVolumeDirection
import kotlin.math.abs

internal enum class RotaryInputKind { Motion, NavigationKey, VolumeKey }

/** Positive pixels always mean scrolling towards the end of a list. */
internal data class WatchRotaryEvent(val pixels: Float, val kind: RotaryInputKind = RotaryInputKind.Motion)

internal fun normalizedRotaryPixels(
    generic: Float,
    vertical: Float,
    horizontal: Float,
    verticalFactor: Float,
    horizontalFactor: Float,
): Float {
    // Match AndroidComposeView: raw AXIS_SCROLL/VSCROLL and Compose scroll
    // pixels have opposite signs. HSCROLL already points towards list end.
    val pixels = when {
        generic.isFinite() && generic != 0f -> -generic * verticalFactor
        vertical.isFinite() && vertical != 0f &&
            (!horizontal.isFinite() || abs(vertical) >= abs(horizontal)) -> -vertical * verticalFactor
        horizontal.isFinite() -> horizontal * horizontalFactor
        else -> 0f
    }
    return pixels.takeIf(Float::isFinite) ?: 0f
}

internal fun rotaryKeyDirection(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> -1
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> 1
    else -> hardwareVolumeDirection(keyCode)?.let { -it }
}

/** Fractional high-resolution motion must not raise volume once per tiny sample. */
internal class RotaryVolumeAccumulator {
    private var pending = 0f

    fun step(event: WatchRotaryEvent, thresholdPx: Float): Int? {
        val pixels = event.pixels
        if (!pixels.isFinite() || pixels == 0f) return null
        if (event.kind != RotaryInputKind.Motion) {
            pending = 0f
            return rotaryVolumeDirection(pixels)
        }
        val threshold = thresholdPx.takeIf { it.isFinite() && it > 0f } ?: return null
        if (pending * pixels < 0f) pending = 0f
        pending = (pending + pixels).coerceIn(-threshold, threshold)
        if (abs(pending) < threshold) return null
        val direction = rotaryVolumeDirection(pending)
        // At most one system step per event; never enqueue a large turn for
        // later, or replay it after opening another page/controller connection.
        pending = 0f
        return direction
    }
}

/**
 * Activity/window-level routing avoids reliance on a Compose control keeping
 * focus. Only the foremost resumed screen/dialog receives input, never a
 * broadcast to every composed list. No model names or private vendor APIs.
 */
internal class WatchRotaryDispatcher(context: Context) {
    private class Target(val priority: Int, val handler: (WatchRotaryEvent) -> Boolean)
    private data class Press(val deviceId: Int, val keyCode: Int)
    private val targets = mutableListOf<Target>()
    private val presses = mutableMapOf<Press, Target>()
    private val diagnosed = mutableSetOf<String>()
    private val config by lazy { ViewConfiguration.get(context) }
    private val verticalFactor by lazy { ViewConfigurationCompat.getScaledVerticalScrollFactor(config, context) }
    private val horizontalFactor by lazy { ViewConfigurationCompat.getScaledHorizontalScrollFactor(config, context) }
    private val keyScrollPixels = 36f * context.resources.displayMetrics.density
    private val target: Target? get() = targets.asReversed().maxByOrNull { it.priority }

    fun register(priority: Int = 0, handler: (WatchRotaryEvent) -> Boolean): () -> Unit {
        val entry = Target(priority, handler)
        targets.add(entry)
        return { targets.remove(entry) }
    }

    fun dispatchMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val receiver = target ?: return false
        val generic = event.getAxisValue(MotionEventCompat.AXIS_SCROLL)
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val pixels = normalizedRotaryPixels(generic, vertical, horizontal, verticalFactor, horizontalFactor)
        if (pixels == 0f) return false
        val consumed = receiver.handler(WatchRotaryEvent(pixels))
        if (consumed) diagnose("motion source=${event.source} axis=${when {
            generic.isFinite() && generic != 0f -> MotionEventCompat.AXIS_SCROLL
            vertical.isFinite() && vertical != 0f && (!horizontal.isFinite() || abs(vertical) >= abs(horizontal)) -> MotionEvent.AXIS_VSCROLL
            else -> MotionEvent.AXIS_HSCROLL
        }}")
        return consumed
    }

    fun dispatchKey(event: KeyEvent, textInputActive: Boolean = false): Boolean {
        val direction = rotaryKeyDirection(event.keyCode) ?: return false
        val press = Press(event.deviceId, event.keyCode)
        // Consume the matching key-up (and repeats) even if a dialog/page has
        // since changed. Never let a handled crown tick adjust system volume too.
        if (event.action == KeyEvent.ACTION_UP) return presses.remove(press) != null
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) {
            val owner = presses[press] ?: return false
            if (target === owner && !textInputActive) owner.handler(keyEvent(event, direction))
            return true
        }
        val isVolume = hardwareVolumeDirection(event.keyCode) != null
        if (!isVolume && (textInputActive ||
                event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 ||
                event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)) return false
        val receiver = target ?: return false
        if (!receiver.handler(keyEvent(event, direction))) return false
        presses[press] = receiver
        diagnose("key source=${event.source} code=${event.keyCode}")
        return true
    }

    fun clearPressedKeys() = presses.clear()

    private fun keyEvent(event: KeyEvent, direction: Int) = WatchRotaryEvent(
        direction * keyScrollPixels,
        if (hardwareVolumeDirection(event.keyCode) == null) RotaryInputKind.NavigationKey else RotaryInputKind.VolumeKey,
    )

    private fun diagnose(description: String) {
        // No touch coordinates, device names, account data or per-tick log spam.
        if (diagnosed.size < 16 && diagnosed.add(description)) AppLog.write("ROTARY", description)
    }
}
