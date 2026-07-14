package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.highlightedCharacters
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesAndMergesTranslation() {
        val lines = LrcParser.parse("[00:01.50]你好\n[00:03]腕上", "[00:01.720]Hello")
        assertEquals(1500, lines[0].timeMs)
        assertEquals("Hello", lines[0].translation)
        assertEquals("腕上", lines[1].text)
    }

    @Test fun parsesQrcWordTimingAndHighlightsByWordProgress() {
        val lines = LrcParser.parse("[00:01]你好", wordSync = "[1000,1000]你(1000,400)好(1400,600)")
        assertEquals("你好", lines.single().text)
        assertEquals(2, lines.single().words.size)
        assertEquals(1, highlightedCharacters(lines.single(), 1_200, 2_000))
        assertEquals(2, highlightedCharacters(lines.single(), 1_450, 2_000))
    }

    @Test fun lrcFallsBackToEvenCharacterProgress() {
        val line = LrcParser.parse("[00:01]四个文字").single()
        assertEquals(2, highlightedCharacters(line, 3_000, 5_000))
    }
}
