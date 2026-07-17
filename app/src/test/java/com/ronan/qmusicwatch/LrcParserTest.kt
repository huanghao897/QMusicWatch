package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.highlightedCharacters
import com.ronan.qmusicwatch.lyrics.activeLyricIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun fallsBackToUntimedPlainTextAndAlignsPlainTranslation() {
        val lines = LrcParser.parse("[ti:Demo]\nFirst line\nSecond line", "第一行\n第二行")
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertEquals(listOf(-1L, -1L), lines.map { it.timeMs })
        assertEquals(listOf("第一行", "第二行"), lines.map { it.translation })
    }

    @Test fun keepsOriginalVisibleWhenRequestedTranslationDoesNotExist() {
        assertEquals(true to false, lyricLayers(showOriginal = false, showTranslation = true, hasTranslation = false))
        assertEquals(false to true, lyricLayers(showOriginal = false, showTranslation = true, hasTranslation = true))
    }

    @Test fun noLineIsActiveBeforeTheFirstTimestampOrForPlainText() {
        val timed = LrcParser.parse("[00:05]Starts later")
        assertEquals(-1, activeLyricIndex(timed, 4_999))
        assertEquals(0, activeLyricIndex(timed, 5_000))
        assertEquals(-1, activeLyricIndex(LrcParser.parse("Plain line"), 99_000))
    }
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

    @Test fun invalidWordSyncMetadataDoesNotHideLrc() {
        assertEquals("歌词仍在", LrcParser.parse("[00:01]歌词仍在", wordSync = "1").single().text)
    }
}
