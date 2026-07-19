package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.UserProfile
import com.ronan.qmusicwatch.model.QUALITY_HI_RES
import com.ronan.qmusicwatch.model.QUALITY_HQ
import com.ronan.qmusicwatch.model.QUALITY_SQ
import com.ronan.qmusicwatch.model.QUALITY_STANDARD
import com.ronan.qmusicwatch.model.normalizeQualityId
import com.ronan.qmusicwatch.model.qualityAvailability
import com.ronan.qmusicwatch.model.qualityFallbackOrder
import com.ronan.qmusicwatch.model.resolveQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipQualityTest {
    private val track = Track("mid", "Song", qualities = listOf(QUALITY_STANDARD, QUALITY_HQ, QUALITY_SQ, QUALITY_HI_RES))

    @Test fun membershipProfileNeverHardBlocksAnUpstreamQualityCheck() {
        val profile = UserProfile(isVip = false, vipName = "普通账号")
        val options = qualityAvailability(track, profile, nowMillis = 1_700_000_000_000L)
        assertTrue(options.first { it.id == QUALITY_STANDARD }.available)
        assertTrue(options.first { it.id == QUALITY_HQ }.available)
        assertTrue(options.first { it.id == QUALITY_HQ }.reason.contains("QQ 音乐"))
        assertEquals(QUALITY_HQ, resolveQuality(QUALITY_HQ, track, profile, 1_700_000_000_000L).resolved)
    }

    @Test fun activeMusicMembershipCanSelectSqWhenTrackProvidesIt() {
        val profile = UserProfile(isVip = true, vipExpireAt = 1_800_000_000L, vipName = "豪华绿钻")
        val options = qualityAvailability(track, profile, nowMillis = 1_700_000_000_000L)
        assertTrue(options.first { it.id == QUALITY_SQ }.available)
        assertEquals(QUALITY_SQ, resolveQuality(QUALITY_SQ, track, profile, 1_700_000_000_000L).resolved)
    }

    @Test fun unverifiedHiResFallsBackWithoutUsingAnAmbiguousFilePrefix() {
        val profile = UserProfile(isVip = true, vipExpireAt = 1_800_000_000L, vipName = "超级会员（SVIP）")
        val resolution = resolveQuality(QUALITY_HI_RES, track, profile, nowMillis = 1_700_000_000_000L)
        assertEquals(QUALITY_SQ, resolution.resolved)
        assertFalse(resolution.requestedAvailable)
        assertEquals(listOf(QUALITY_SQ, QUALITY_HQ, QUALITY_STANDARD), qualityFallbackOrder(QUALITY_HI_RES))
    }

    @Test fun legacyQualityValuesMigrateToOfficialTierIds() {
        assertEquals(QUALITY_STANDARD, normalizeQualityId("128"))
        assertEquals(QUALITY_HQ, normalizeQualityId("320k"))
        assertEquals(QUALITY_SQ, normalizeQualityId("flac"))
        assertEquals(QUALITY_HI_RES, normalizeQualityId("flac24bit"))
    }
}
