package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.lyrics.activeLyricIndex
import com.ronan.qmusicwatch.ui.watchLongClickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchUiLogicTest {
    @Test fun lyricOffsetLabelsDescribeExistingStoredValueSemantics() {
        assertEquals(
            mapOf(
                "-1000" to "延后 1 秒",
                "-500" to "延后 0.5 秒",
                "0" to "无偏移",
                "500" to "提前 0.5 秒",
                "1000" to "提前 1 秒",
            ),
            lyricOffsetChoices.toMap(),
        )
        val lines = listOf(LyricLine(1_000L, "第一句"))
        assertEquals(0, activeLyricIndex(lines, 500L + 500L))
        assertEquals(-1, activeLyricIndex(lines, 1_499L - 500L))
        assertEquals(0, activeLyricIndex(lines, 1_500L - 500L))
    }

    @Test fun ordinaryIconButtonDoesNotRegisterANoopLongClick() {
        var haptics = 0
        assertNull(watchLongClickAction(null) { haptics++ })
        assertEquals(0, haptics)
    }

    @Test fun realIconButtonLongClickKeepsItsFeedbackAndAction() {
        val events = mutableListOf<String>()
        val action = watchLongClickAction({ events += "longClick" }) { events += "haptic" }
        requireNotNull(action).invoke()
        assertEquals(listOf("haptic", "longClick"), events)
    }
}
