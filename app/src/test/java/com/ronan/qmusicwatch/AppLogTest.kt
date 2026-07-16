package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.data.redactLogMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test fun removesPlaybackUrlsAndSessionSecrets() {
        val result = redactLogMessage(
            "failed https://isure.stream.qqmusic.qq.com/M500.mp3?vkey=secret qm_keyst=abc; qrcode_id:qr-123 Authorization=Bearer-456\nnext",
        )
        assertFalse(result.contains("isure.stream"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("abc"))
        assertFalse(result.contains("qr-123"))
        assertFalse(result.contains("Bearer-456"))
        assertTrue(result.contains("<url>"))
        assertTrue(result.contains("<redacted>"))
        assertFalse(result.contains('\n'))
    }
}
