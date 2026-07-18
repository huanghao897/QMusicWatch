package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.data.redactLogMessage
import com.ronan.qmusicwatch.data.redactDiagnosticMessage
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

    @Test fun removesEveryUpstreamCredentialAlias() {
        val result = redactLogMessage("musickey=music-secret refresh_key=refresh-secret qrsig=qr-secret ptqrtoken=12345")
        assertFalse(result.contains("music-secret"))
        assertFalse(result.contains("refresh-secret"))
        assertFalse(result.contains("qr-secret"))
        assertFalse(result.contains("12345"))
    }

    @Test fun uploadedDiagnosticsAlsoRemoveAccountAndTrackIdentifiers() {
        val result = redactDiagnosticMessage(
            "track=001 account=10001 uin:20002 media_id=abc song_id=42 qq=456 query=Ronan keyword:'watch music' user_name=Ronan https://example.com/file",
        )
        assertFalse(result.contains("001"))
        assertFalse(result.contains("10001"))
        assertFalse(result.contains("20002"))
        assertFalse(result.contains("abc"))
        assertFalse(result.contains("42"))
        assertFalse(result.contains("456"))
        assertFalse(result.contains("watch music"))
        assertFalse(result.contains("Ronan"))
        assertFalse(result.contains("example.com"))
    }

    @Test fun uploadedDiagnosticsRemoveJsonSearchFields() {
        val result = redactDiagnosticMessage("{\"query\":\"Taylor Swift\",\"song_id\":\"123\"}")
        assertFalse(result.contains("Taylor Swift"))
        assertFalse(result.contains("123"))
    }
}
