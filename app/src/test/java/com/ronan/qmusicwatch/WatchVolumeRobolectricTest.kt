package com.ronan.qmusicwatch

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.ronan.qmusicwatch.input.WatchRotaryDispatcher
import com.ronan.qmusicwatch.playback.DeviceVolumeState
import com.ronan.qmusicwatch.ui.LocalWatchRotaryDispatcher
import com.ronan.qmusicwatch.ui.QMusicWatchTheme
import com.ronan.qmusicwatch.ui.WatchVolumeContent
import com.ronan.qmusicwatch.ui.WatchVolumeDialog
import com.ronan.qmusicwatch.ui.canStepDeviceVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 35], qualifiers = "w240dp-h240dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WatchVolumeRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun volumeButtonFitsTheExistingQuickActionStripAndOpensTheControl() {
        var opened = false
        compose.setContent {
            QMusicWatchTheme(uiSize = "compact", pureBlack = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerQuickActions(false, {}, {}, { opened = true }, {})
                }
            }
        }
        listOf("播放队列", "喜欢", "调节音量", "播放选项").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("调节音量").performClick()
        assertTrue(opened)
    }

    @Test fun touchButtonsUseRelativeStepsAndRespectTheVolumeLimits() {
        val volume = mutableStateOf(DeviceVolumeState(0, 2))
        val steps = mutableListOf<Int>()
        var dismissed = false
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                WatchVolumeContent(volume.value, { direction ->
                    steps += direction
                    volume.value = volume.value.copy(current = volume.value.current + direction)
                    true
                }, { dismissed = true })
            }
        }
        compose.onNodeWithContentDescription("减小音量").assertIsNotEnabled()
        compose.onNodeWithContentDescription("增大音量").assertIsEnabled().performClick()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithContentDescription("增大音量").performClick()
        compose.onNodeWithContentDescription("增大音量").assertIsNotEnabled()
        compose.onNodeWithContentDescription("减小音量").performClick()
        assertEquals(listOf(1, 1, -1), steps)
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(dismissed)
    }

    @Test fun unknownVolumeIsNotShownAsZeroOrUsedForAnAbsoluteAdjustment() {
        compose.setContent {
            QMusicWatchTheme("compact", true) { WatchVolumeContent(null, { error("Unknown volume") }, {}) }
        }
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithContentDescription("增大音量").assertIsNotEnabled()
        compose.onNodeWithContentDescription("减小音量").assertIsNotEnabled()
        assertFalse(canStepDeviceVolume(DeviceVolumeState(1, 0), 1))
        assertFalse(canStepDeviceVolume(DeviceVolumeState(1, 2), 0))
    }

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun rightSwipeClosesTheModalAndReturnsToThePlayerWithoutChangingVolume() {
        val visible = mutableStateOf(true)
        var adjustments = 0
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                Text("播放器")
                if (visible.value) {
                    WatchVolumeDialog(DeviceVolumeState(5, 10), true, {}, { adjustments++; true }, { visible.value = false })
                }
            }
        }
        compose.onNode(isDialog()).performTouchInput { swipeRight() }
        compose.onNodeWithText("音量").assertDoesNotExist()
        compose.onNodeWithText("播放器").assertIsDisplayed()
        assertEquals(0, adjustments)
    }

    @Test fun shortReverseVerticalAndCancelledSwipesDoNotDismissOrChangeVolume() {
        var dismissals = 0
        var adjustments = 0
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                WatchVolumeContent(DeviceVolumeState(5, 10), { adjustments++; true }, { dismissals++ })
            }
        }
        compose.onRoot().performTouchInput {
            swipe(center, center + Offset(width * .1f, 0f))
            swipeLeft()
            swipeDown()
            down(centerLeft)
            moveTo(centerRight)
            cancel()
        }
        assertEquals(0, dismissals)
        assertEquals(0, adjustments)
        compose.onRoot().performTouchInput { swipeRight() }
        assertEquals(1, dismissals)
    }

    @Test fun swipeStartingOnAVolumeButtonDoesNotAlsoPressTheButton() {
        var dismissals = 0
        var adjustments = 0
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                WatchVolumeContent(DeviceVolumeState(5, 10), { adjustments++; true }, { dismissals++ })
            }
        }
        compose.onNodeWithContentDescription("减小音量").performTouchInput {
            swipe(center, center + Offset(width.toFloat(), 0f))
        }
        assertEquals(1, dismissals)
        assertEquals(0, adjustments)
    }

    @Test fun volumeRefreshDuringASwipeDoesNotCancelTheReturnGesture() {
        val volume = mutableStateOf(DeviceVolumeState(5, 10))
        var dismissals = 0
        compose.setContent {
            QMusicWatchTheme("compact", true) {
                WatchVolumeContent(volume.value, { true }, { dismissals++ })
            }
        }
        compose.onRoot().performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        compose.runOnIdle { volume.value = DeviceVolumeState(6, 10) }
        compose.onRoot().performTouchInput {
            moveTo(centerRight)
            up()
        }
        assertEquals(1, dismissals)
    }

    @Test
    @Config(qualifiers = "w240dp-h240dp-round-xhdpi")
    fun compactRoundScreenKeepsTouchTargetsInsideTheVisibleCircle() {
        compose.setContent {
            QMusicWatchTheme("compact", true) { WatchVolumeContent(DeviceVolumeState(5, 10), { true }, {}) }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val center = root.center
        val radiusSquared = minOf(root.width, root.height).let { it * it / 4f }
        listOf("返回", "减小音量", "增大音量").forEach { label ->
            val node = compose.onNodeWithContentDescription(label)
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight).forEach { corner ->
                val delta = corner - center
                assertTrue("$label lies outside the round screen: $bounds", delta.getDistanceSquared() <= radiusSquared)
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h320dp-xhdpi")
    fun controlsAlsoRemainVisibleOnALargerSquareWatch() {
        compose.setContent {
            QMusicWatchTheme("large", true) { WatchVolumeContent(DeviceVolumeState(10, 15), { true }, {}) }
        }
        compose.onNodeWithText("67%").assertIsDisplayed()
        compose.onNodeWithContentDescription("增大音量").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test fun modalCrownRespectsTheSettingWithoutSendingInputToThePlayer() {
        val dispatcher = WatchRotaryDispatcher(RuntimeEnvironment.getApplication())
        var behind = 0
        val unregister = dispatcher.register { behind++; true }
        var adjustments = 0
        val crownEnabled = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalWatchRotaryDispatcher provides dispatcher) {
                QMusicWatchTheme("compact", true) {
                    WatchVolumeDialog(DeviceVolumeState(5, 10), crownEnabled.value, {}, { adjustments++; true }, {})
                }
            }
        }
        fun turn() {
            assertTrue(dispatcher.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            assertTrue(dispatcher.dispatchKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
        }
        compose.runOnIdle { turn() }
        assertEquals(0, adjustments)
        // Disabling the crown must not disable the explicit touch control.
        compose.onNodeWithContentDescription("增大音量").performClick()
        assertEquals(1, adjustments)
        compose.runOnIdle { crownEnabled.value = true }
        compose.runOnIdle { turn() }
        assertEquals(2, adjustments)
        assertEquals(0, behind)
        unregister()
    }
}
