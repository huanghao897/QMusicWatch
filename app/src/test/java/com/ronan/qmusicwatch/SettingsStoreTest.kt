package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.data.normalizeLyricAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    @Test fun lyricAlignmentAcceptsOnlySupportedModes() {
        assertEquals("left", normalizeLyricAlignment(null))
        assertEquals("left", normalizeLyricAlignment("left"))
        assertEquals("center", normalizeLyricAlignment("center"))
        assertEquals("left", normalizeLyricAlignment("invalid"))
    }

    @Test fun lyricTimePillIsVisibleOnlyForTheManualFocusedTimedLine() {
        assertEquals(true, showLyricTimePill(index = 3, focusedIndex = 3, manualSelection = true, timeMs = 12_000))
        assertEquals(false, showLyricTimePill(index = 2, focusedIndex = 3, manualSelection = true, timeMs = 10_000))
        assertEquals(false, showLyricTimePill(index = 3, focusedIndex = 3, manualSelection = false, timeMs = 12_000))
        assertEquals(false, showLyricTimePill(index = 3, focusedIndex = 3, manualSelection = true, timeMs = -1))
    }

    @Test fun lyricCandidateUsesTheLineClosestToViewportCenter() {
        val items = listOf(
            Triple(7, 20, 40),
            Triple(8, 80, 40),
            Triple(9, 140, 40),
        )

        assertEquals(8, lyricIndexClosestToCenter(0, 220, items))
        assertEquals(-1, lyricIndexClosestToCenter(0, 0, items))
    }

    @Test fun lyricTimeUsesOfficialTwoDigitMinuteFormat() {
        assertEquals("00:03", lyricTime(3_000))
        assertEquals("12:05", lyricTime(725_000))
        assertEquals("00:00", lyricTime(-1))
    }
}
