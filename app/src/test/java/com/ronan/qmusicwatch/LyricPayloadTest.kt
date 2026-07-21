package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.decodeQqLyricText
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricPayloadTest {
    @Test fun decodesValidBase64Utf8Lyrics() {
        val lyric = "[00:01.20]First line\n[00:03.00]第二行"
        val encoded = Base64.getEncoder().encodeToString(lyric.encodeToByteArray())
        assertEquals(lyric, decodeQqLyricText(encoded))
    }

    @Test fun preservesPlainTimedAndUntimedLyrics() {
        val timed = "[00:01.20]原样显示"
        val untimed = "词：作者\n这是一首没有时间轴的歌词"
        assertEquals(timed, decodeQqLyricText(timed))
        assertEquals(untimed, decodeQqLyricText(untimed))
    }

    @Test fun preservesMalformedOrNonUtf8Base64() {
        assertEquals("QUJDRA===", decodeQqLyricText("QUJDRA==="))
        assertEquals("/w==", decodeQqLyricText("/w=="))
    }
}
