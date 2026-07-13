package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseUserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UserProfileParserTest {
    @Test fun parsesNicknameAndProtocolRelativeAvatar() {
        val profile = parseUserProfile(Json.parseToJsonElement("""{"creator":{"nick":"Ronan","logo":"//img.qq.com/a.png"},"background":{"picurl":"https://img.qq.com/wallpaper.jpg"},"greenVip":{"isVip":0},"superVip":{"isSVip":1,"vipEndTime":1893456000}}"""))!!
        assertEquals("Ronan", profile.displayName)
        assertEquals("https://img.qq.com/a.png", profile.avatarUrl)
        assertEquals(true, profile.isVip)
        assertEquals(1893456000L, profile.vipExpireAt)
        assertEquals("超级会员（SVIP）", profile.vipName)
    }

    @Test fun ignoresSongVipFlagsWhenParsingAccount() {
        val profile = parseUserProfile(Json.parseToJsonElement("""{"info":{"nick":"Ronan","logo":"https://img.qq.com/a.png"},"song":{"title":"测试歌曲","isVip":0}}"""))!!
        assertEquals(null, profile.isVip)
    }

    @Test fun parsesDedicatedVipResponse() {
        val profile = parseUserProfile(Json.parseToJsonElement("""{"vip_info":{"vip_type":11,"vip_name":"超级会员","expire_date":"20260722"}}"""))!!
        assertEquals(true, profile.isVip)
        assertEquals("超级会员（SVIP）", profile.vipName)
        assertEquals(1784649600L, profile.vipExpireAt)
    }

    @Test fun parsesRealVipLoginBaseShape() {
        val profile = parseUserProfile(Json.parseToJsonElement("""{"vip_response":{"svip":1,"plus":{"vip":1,"huge_vip":1},"userinfo":{"expire":1784649600,"music_level":8}}}"""))!!
        assertEquals(true, profile.isVip)
        assertEquals("超级会员（SVIP）", profile.vipName)
        assertEquals(1784649600L, profile.vipExpireAt)
    }
}
