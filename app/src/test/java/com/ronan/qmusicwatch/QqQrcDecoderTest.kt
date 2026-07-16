package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.QqQrcDecoder
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class QqQrcDecoderTest {
    @Test fun qqDesCoreMatchesRealFirstStage() {
        assertEquals("B35AD16943327F27", QqQrcDecoder.desBlockForTest("28EAF5BDF9C72A33", "21402329284E484C", decrypt = true))
    }

    @Test fun decodesSyntheticQqMusicQrcFixtureIntoTimedLyrics() {
        val expected = """<?xml version="1.0" encoding="utf-8"?><QrcInfos><LyricInfo><Lyric_1 LyricType="1" LyricContent="[0,2000]First line(0,900) test(900,1100)&#13;&#10;[2000,1800]Second(2000,800) line(2800,1000)"/></LyricInfo></QrcInfos>"""
        val encrypted = QqQrcDecoder.encodeForTest(expected)
        val xml = QqQrcDecoder.decode(encrypted)
        assertEquals(expected, xml)
        val lines = LrcParser.parse(original = "", wordSync = xml)
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.words.isNotEmpty() })
        assertEquals(2_000L, lines[1].timeMs)
    }
}
