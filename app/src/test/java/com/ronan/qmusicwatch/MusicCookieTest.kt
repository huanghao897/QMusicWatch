package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.login.MusicCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicCookieTest {
    @Test fun extractsAndValidatesAccount() {
        assertEquals("12345", MusicCookie.accountId("qm_keyst=x; uin=o12345"))
        assertEquals("wx_user", MusicCookie.accountId("wxuin=wx_user; qqmusic_key=x"))
        assertNull(MusicCookie.accountId("uin=../../bad; qqmusic_key=x"))
    }

    @Test fun extractsTemporaryCookiesFromOfficialPageMessage() {
        val message = """{"code":"qqmusic","cookies":{"qqmusic_key":{"value":"token"},"qqmusic_uin":"12345","qrcode_id":{"value":"qr-id"}}}"""
        assertEquals("qqmusic_key=token; qqmusic_uin=12345; qrcode_id=qr-id", MusicCookie.fromQrMessage(message))
        assertNull(MusicCookie.fromQrMessage("""{"code":"qqmusic","cookies":{"qqmusic_key":"token"}}"""))
    }
}
