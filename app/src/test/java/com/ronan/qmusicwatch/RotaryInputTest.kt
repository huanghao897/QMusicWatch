package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.playback.DeviceVolumeState
import com.ronan.qmusicwatch.playback.hardwareVolumeDirection
import com.ronan.qmusicwatch.playback.rotaryScrollDelta
import com.ronan.qmusicwatch.playback.rotaryVolumeDirection
import com.ronan.qmusicwatch.input.normalizedRotaryPixels
import com.ronan.qmusicwatch.input.RotaryInputKind
import com.ronan.qmusicwatch.input.RotaryVolumeAccumulator
import com.ronan.qmusicwatch.input.WatchRotaryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotaryInputTest {
    @Test fun prefersTheAxisThatContainsTheCrownMovement() {
        assertEquals(-4f, rotaryScrollDelta(-4f, 1f))
        assertEquals(3f, rotaryScrollDelta(0f, 3f))
        assertEquals(0f, rotaryScrollDelta(Float.NaN, Float.NaN))
    }

    @Test fun mapsRotaryDirectionToVolumeSteps() {
        assertEquals(1, rotaryVolumeDirection(-0.1f))
        assertEquals(-1, rotaryVolumeDirection(0.1f))
        assertNull(rotaryVolumeDirection(0f))
        assertNull(rotaryVolumeDirection(Float.POSITIVE_INFINITY))
    }

    @Test fun validAxisSurvivesANonFiniteOtherAxis() {
        assertEquals(-2f, rotaryScrollDelta(-2f, Float.NaN))
        assertEquals(2f, rotaryScrollDelta(Float.POSITIVE_INFINITY, 2f))
        assertEquals(2f, rotaryScrollDelta(2f, Float.NEGATIVE_INFINITY))
        assertEquals(0f, rotaryScrollDelta(Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test fun rawMotionUsesComposeDirectionAndConfiguredPixelScale() {
        assertEquals(-24f, normalizedRotaryPixels(.5f, 0f, 0f, 48f, 24f))
        assertEquals(24f, normalizedRotaryPixels(0f, -.5f, 0f, 48f, 24f))
        assertEquals(12f, normalizedRotaryPixels(0f, 0f, .5f, 48f, 24f))
        assertEquals(1, rotaryVolumeDirection(normalizedRotaryPixels(1f, 0f, 0f, 48f, 24f)))
    }

    @Test fun genericAxisTakesPriorityAndInvalidAxesCanFallBack() {
        assertEquals(-48f, normalizedRotaryPixels(1f, -9f, 0f, 48f, 24f))
        assertEquals(-48f, normalizedRotaryPixels(Float.NaN, 1f, Float.POSITIVE_INFINITY, 48f, 24f))
        assertEquals(24f, normalizedRotaryPixels(Float.NaN, Float.NaN, 1f, 48f, 24f))
        assertEquals(0f, normalizedRotaryPixels(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, 48f, 24f))
    }

    @Test fun highResolutionCrownAccumulatesSmallDeltas() {
        val input = RotaryVolumeAccumulator()
        repeat(3) { assertNull(input.step(WatchRotaryEvent(-4f), 16f)) }
        assertEquals(1, input.step(WatchRotaryEvent(-4f), 16f))
        assertNull(input.step(WatchRotaryEvent(-4f), 16f))
    }

    @Test fun reversingCrownDiscardsOppositeFractionalMotion() {
        val input = RotaryVolumeAccumulator()
        assertNull(input.step(WatchRotaryEvent(-12f), 16f))
        assertNull(input.step(WatchRotaryEvent(8f), 16f))
        assertEquals(-1, input.step(WatchRotaryEvent(8f), 16f))
    }

    @Test fun largeDeltasNeverQueueExtraVolumeSteps() {
        val input = RotaryVolumeAccumulator()
        assertEquals(1, input.step(WatchRotaryEvent(-10_000f), 16f))
        assertNull(input.step(WatchRotaryEvent(0f), 16f))
        assertNull(input.step(WatchRotaryEvent(-1f), 16f))
        assertNull(input.step(WatchRotaryEvent(Float.NaN), 16f))
    }

    @Test fun keyEmittingCrownsTakeOneStepWithoutADeadZone() {
        val input = RotaryVolumeAccumulator()
        assertEquals(-1, input.step(WatchRotaryEvent(1f, RotaryInputKind.VolumeKey), 16f))
        assertEquals(1, input.step(WatchRotaryEvent(-1f, RotaryInputKind.NavigationKey), 16f))
    }

    @Test fun mapsHardwareVolumeKeysToVolumeSteps() {
        assertEquals(1, hardwareVolumeDirection(android.view.KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(-1, hardwareVolumeDirection(android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(hardwareVolumeDirection(android.view.KeyEvent.KEYCODE_VOLUME_MUTE))
    }

    @Test fun volumeStateReportsAClampedPercentage() {
        assertEquals(50, DeviceVolumeState(5, 10).percent)
        assertEquals(0, DeviceVolumeState(-3, 10).percent)
        assertEquals(100, DeviceVolumeState(20, 10).percent)
        assertNull(DeviceVolumeState(1, 0).percent)
    }
}
