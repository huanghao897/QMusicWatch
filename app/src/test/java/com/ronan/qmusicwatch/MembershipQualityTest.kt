package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.UserProfile
import com.ronan.qmusicwatch.model.qualityAvailability
import com.ronan.qmusicwatch.model.resolveQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipQualityTest {
    private val track = Track("mid", "Song", qualities = listOf("128", "320"))

    @Test fun nonVipAccountCannotRequestHighQuality() {
        val profile = UserProfile(isVip = false, vipName = "普通账号")
        val options = qualityAvailability(track, profile, nowMillis = 1_700_000_000_000L)
        assertTrue(options.first { it.id == "128" }.available)
        assertFalse(options.first { it.id == "320" }.available)
        assertEquals("128", resolveQuality("320", track, profile, 1_700_000_000_000L).resolved)
        assertTrue(resolveQuality("320", track, profile, 1_700_000_000_000L).reason.contains("会员"))
    }

    @Test fun activeVipAccountCanUseHighQualityWhenTrackProvidesIt() {
        val profile = UserProfile(isVip = true, vipExpireAt = 1_800_000_000L, vipName = "豪华绿钻")
        val options = qualityAvailability(track, profile, nowMillis = 1_700_000_000_000L)
        assertTrue(options.first { it.id == "320" }.available)
        assertEquals("320", resolveQuality("320", track, profile, 1_700_000_000_000L).resolved)
    }

    @Test fun expiredVipFallsBackEvenWhenCachedFlagIsTrue() {
        val profile = UserProfile(isVip = true, vipExpireAt = 1_600_000_000L, vipName = "超级会员（SVIP）")
        val resolution = resolveQuality("320", track, profile, nowMillis = 1_700_000_000_000L)
        assertEquals("128", resolution.resolved)
        assertFalse(resolution.requestedAvailable)
    }
}
