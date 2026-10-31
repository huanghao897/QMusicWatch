package com.ronan.qmusicwatch

import android.app.Application
import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import com.ronan.qmusicwatch.input.RotaryInputKind
import com.ronan.qmusicwatch.input.WatchRotaryDispatcher
import com.ronan.qmusicwatch.input.WatchRotaryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class WatchRotaryDispatcherRobolectricTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun standardEncoderWorksWithoutAnyComposeFocus() {
        val router = WatchRotaryDispatcher(context)
        val received = mutableListOf<WatchRotaryEvent>()
        router.register { received.add(it); true }
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f) { assertTrue(router.dispatchMotion(it)) }
        assertEquals(1, received.size)
        assertTrue(received.single().pixels < 0f)
        assertEquals(RotaryInputKind.Motion, received.single().kind)
    }

    @Test fun vendorEventsDoNotRequireTheRotarySourceFlag() {
        val router = WatchRotaryDispatcher(context)
        val received = mutableListOf<Float>()
        router.register { received.add(it.pixels); true }
        withMotion(MotionEventCompat.AXIS_SCROLL, -1f, source = 0) { assertTrue(router.dispatchMotion(it)) }
        withMotion(MotionEvent.AXIS_VSCROLL, -1f, source = InputDevice.SOURCE_MOUSE) { assertTrue(router.dispatchMotion(it)) }
        withMotion(MotionEvent.AXIS_HSCROLL, 1f, source = InputDevice.SOURCE_MOUSE) { assertTrue(router.dispatchMotion(it)) }
        assertEquals(3, received.size)
        assertTrue(received.all { it > 0f })
    }

    @Test fun noActiveConsumerLeavesInputAlone() {
        val router = WatchRotaryDispatcher(context)
        withMotion(MotionEventCompat.AXIS_SCROLL, -1f) { assertFalse(router.dispatchMotion(it)) }
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
    }

    @Test fun onlyForemostConsumerReceivesMotionAndItsRejectionDoesNotLeak() {
        val router = WatchRotaryDispatcher(context)
        var playerCalls = 0
        var dialogCalls = 0
        router.register { playerCalls++; true }
        router.register(priority = 100) { dialogCalls++; false }
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f) { assertFalse(router.dispatchMotion(it)) }
        assertEquals(0, playerCalls)
        assertEquals(1, dialogCalls)
    }

    @Test fun unregisteringDialogRestoresTheVisiblePage() {
        val router = WatchRotaryDispatcher(context)
        var pageCalls = 0
        var dialogCalls = 0
        router.register { pageCalls++; true }
        val close = router.register(priority = 100) { dialogCalls++; true }
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f) { assertTrue(router.dispatchMotion(it)) }
        close()
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f) { assertTrue(router.dispatchMotion(it)) }
        assertEquals(1, pageCalls)
        assertEquals(1, dialogCalls)
    }

    @Test fun recentlyResumedPageWinsAtEqualPriority() {
        val router = WatchRotaryDispatcher(context)
        var oldCalls = 0
        var newCalls = 0
        router.register { oldCalls++; true }
        router.register { newCalls++; true }
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f) { router.dispatchMotion(it) }
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test fun keyCrownsSupportDirectionPageAndSystemNavigationKeys() {
        val router = WatchRotaryDispatcher(context)
        val received = mutableListOf<WatchRotaryEvent>()
        router.register { received.add(it); true }
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP).forEach {
            assertTrue(router.dispatchKey(key(it)))
            assertTrue(router.dispatchKey(key(it, KeyEvent.ACTION_UP)))
        }
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN).forEach {
            assertTrue(router.dispatchKey(key(it)))
        }
        assertTrue(received.take(3).all { it.pixels < 0f })
        assertTrue(received.drop(3).all { it.pixels > 0f })
        assertTrue(received.all { it.kind == RotaryInputKind.NavigationKey })
    }

    @Test fun handledKeysConsumeRepeatsAndUpButNeverDoubleInvokeOnUp() {
        val router = WatchRotaryDispatcher(context)
        var calls = 0
        router.register { calls++; true }
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP, repeat = 1)))
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)))
        assertEquals(2, calls)
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)))
    }

    @Test fun pageChangeDoesNotReplayAHeldKeyIntoAnotherPage() {
        val router = WatchRotaryDispatcher(context)
        var newCalls = 0
        val close = router.register { true }
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
        close()
        router.register { newCalls++; true }
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP, repeat = 1)))
        assertTrue(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)))
        assertEquals(0, newCalls)
    }

    @Test fun unhandledDownNeverStartsInterceptionHalfwayThroughAKeyPress() {
        val router = WatchRotaryDispatcher(context)
        val close = router.register { false }
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_DOWN)))
        close()
        var calls = 0
        router.register { calls++; true }
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 1)))
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP)))
        assertEquals(0, calls)
    }

    @Test fun textInputDoesNotLoseArrowKeys() {
        val router = WatchRotaryDispatcher(context)
        var calls = 0
        router.register { calls++; true }
        assertFalse(router.dispatchKey(key(KeyEvent.KEYCODE_DPAD_DOWN), textInputActive = true))
        assertEquals(0, calls)
    }

    @Test fun unrelatedKeysAndTouchGesturesAreNeverClaimed() {
        val router = WatchRotaryDispatcher(context)
        var calls = 0
        router.register { calls++; true }
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_VOLUME_MUTE).forEach {
            assertFalse(router.dispatchKey(key(it)))
        }
        withMotion(MotionEventCompat.AXIS_SCROLL, 1f, action = MotionEvent.ACTION_MOVE) { assertFalse(router.dispatchMotion(it)) }
        withMotion(MotionEventCompat.AXIS_SCROLL, 0f) { assertFalse(router.dispatchMotion(it)) }
        assertEquals(0, calls)
    }

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        KeyEvent(10L, 20L, action, code, repeat, 0, 0, 0, 0, InputDevice.SOURCE_DPAD)

    private fun withMotion(
        axis: Int,
        value: Float,
        source: Int = InputDeviceCompat.SOURCE_ROTARY_ENCODER,
        action: Int = MotionEvent.ACTION_SCROLL,
        block: (MotionEvent) -> Unit,
    ) {
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }
        val event = MotionEvent.obtain(10L, 20L, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, source, 0)
        try { block(event) } finally { event.recycle() }
    }
}
