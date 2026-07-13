package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseUserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UserProfileParserTest {
    @Test fun parsesNicknameAndProtocolRelativeAvatar() {
        val profile = parseUserProfile(Json.parseToJsonElement("""{"creator":{"nick":"Ronan","headpic":"//img.qq.com/a.png"},"background":{"picurl":"https://img.qq.com/wallpaper.jpg"},"greenVip":{"isVip":0},"superVip":{"isSVip":1,"vipEndTime":1893456000}}"""))!!
        assertEquals("Ronan", profile.displayName)
        assertEquals("https://img.qq.com/a.png", profile.avatarUrl)
        assertEquals(true, profile.isVip)
        assertEquals(1893456000L, profile.vipExpireAt)
        assertEquals("超级会员", profile.vipName)
    }
}
