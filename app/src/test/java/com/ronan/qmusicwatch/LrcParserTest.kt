package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesAndMergesTranslation() {
        val lines = LrcParser.parse("[00:01.50]你好\n[00:03]腕上", "[00:01.720]Hello")
        assertEquals(1500, lines[0].timeMs)
        assertEquals("Hello", lines[0].translation)
        assertEquals("腕上", lines[1].text)
    }
}
