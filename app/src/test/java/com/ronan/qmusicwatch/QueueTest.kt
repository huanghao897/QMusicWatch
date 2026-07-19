package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import com.ronan.qmusicwatch.model.belongsToAccount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import android.view.KeyEvent
import com.ronan.qmusicwatch.playback.mediaButtonSkipDelta
import com.ronan.qmusicwatch.download.canResumePartialDownload
import com.ronan.qmusicwatch.model.QUALITY_LEGACY_UNKNOWN
import com.ronan.qmusicwatch.model.qualityShortLabel

class QueueTest {
    @Test fun partialDownloadCannotMixDifferentQualityFiles() {
        assertEquals(true, canResumePartialDownload("320", "hq"))
        assertEquals(false, canResumePartialDownload("hq", "sq"))
        assertEquals(false, canResumePartialDownload(null, "standard"))
        assertEquals(false, canResumePartialDownload(QUALITY_LEGACY_UNKNOWN, "standard"))
        assertEquals("旧缓存", qualityShortLabel(QUALITY_LEGACY_UNKNOWN))
    }

    @Test fun headsetNextAndPreviousKeysMapToQueueDirections() {
        assertEquals(1, mediaButtonSkipDelta(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(-1, mediaButtonSkipDelta(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(null, mediaButtonSkipDelta(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }
    @Test fun edgeScrollDirectionMatchesViewportEdges() {
        assertEquals(-1, queueEdgeScrollDirection(20f, 480, 72f))
        assertEquals(0, queueEdgeScrollDirection(240f, 480, 72f))
        assertEquals(1, queueEdgeScrollDirection(470f, 480, 72f))
    }
    @Test fun dragReordersImmediatelyAfterHalfRow() {
        assertEquals(0, queueReorderStep(29f, 60))
        assertEquals(1, queueReorderStep(30f, 60))
        assertEquals(-1, queueReorderStep(-31f, 60))
    }

    @Test fun previewCanCrossSeveralRowsWithoutLosingOrder() {
        var queue = listOf(Track("a", "A"), Track("b", "B"), Track("c", "C"), Track("d", "D"))
        queue = moveQueuePreview(queue, 0, 1)
        queue = moveQueuePreview(queue, 1, 2)
        queue = moveQueuePreview(queue, 2, 3)
        assertEquals(listOf("b", "c", "d", "a"), queue.map(Track::id))
    }

    @Test fun selectedImportAddsOnlyCheckedTracksWithoutDuplicates() {
        val a = Track("a", "A"); val b = Track("b", "B"); val c = Track("c", "C")
        assertEquals(listOf("a", "c"), mergeSelectedQueue(listOf(a), listOf(a, b, c), setOf("a", "c")).map(Track::id))
    }

    @Test fun onlyOwnedPlaylistsWithWritableDirectoryCanReceiveTracks() {
        val owned = com.ronan.qmusicwatch.model.MusicCollection("a", "我的", directoryId = "100", owned = true)
        val collected = com.ronan.qmusicwatch.model.MusicCollection("b", "收藏", directoryId = "200", owned = false)
        val missingDirectory = com.ronan.qmusicwatch.model.MusicCollection("c", "无目录", owned = true)
        assertEquals(listOf("a"), writablePlaylists(listOf(owned, collected, missingDirectory)).map { it.id })
    }
    @Test fun insertsNextWithoutDuplicatingTrack() {
        val a = Track("a", "A")
        val b = Track("b", "B")
        val c = Track("c", "C")
        assertEquals(listOf("a", "c", "b"), insertNext(listOf(a, b, c), "a", c).map { it.id })
        assertEquals(listOf("c"), insertNext(emptyList(), null, c).map { it.id })
    }

    @Test fun resolvesPlaybackModes() {
        assertEquals(1, nextQueueIndex(3, 0, 1, "sequential", false))
        assertEquals(-1, nextQueueIndex(3, 2, 1, "sequential", true))
        assertEquals(0, nextQueueIndex(3, 2, 1, "loop_all", true))
        assertEquals(2, nextQueueIndex(3, 2, 1, "repeat_one", true))
        assertEquals(1, nextQueueIndex(3, 2, 1, "shuffle", true, 1))
    }

    @Test fun dragUsesMeasuredRowHeightAndVisibleQueueOrder() {
        val visible = listOf(1, 4, 7, 9)
        assertEquals(7, queueDropIndex(visible, 1, 76f, 60))
        assertEquals(1, queueDropIndex(visible, 1, -200f, 60))
        assertEquals(9, queueDropIndex(visible, 1, 500f, 60))
    }

    @Test fun profileCacheRefreshesOnlyWhenMissingWrongAccountOrExpired() {
        val now = 2_000_000_000_000L
        val fresh = com.ronan.qmusicwatch.model.CachedUserProfile("1", com.ronan.qmusicwatch.model.UserProfile(displayName = "Ronan", isVip = true, vipExpireAt = now / 1000 + 86_400), now - 100L * 24 * 60 * 60 * 1000)
        assertEquals(false, profileCacheNeedsRefresh(fresh, "1", now))
        assertEquals(true, profileCacheNeedsRefresh(fresh, "2", now))
        assertEquals(false, profileCacheNeedsRefresh(fresh.copy(profile = fresh.profile.copy(isVip = null)), "1", now))
        assertEquals(true, profileCacheNeedsRefresh(fresh.copy(profile = fresh.profile.copy(vipExpireAt = now / 1000)), "1", now))
        assertEquals(false, profileCacheNeedsRefresh(fresh.copy(profile = fresh.profile.copy(isVip = false, vipExpireAt = now / 1000)), "1", now))
    }

    @Test fun dailyRecommendationWrapsWithoutGrowingPastCount() {
        assertEquals(listOf(4, 5, 1), dailyBatch(listOf(1, 2, 3, 4, 5), 3, 3))
        assertEquals(listOf(1, 2), dailyBatch(listOf(1, 2), 0, 10))
    }

    @Test fun downloadProgressShowsKnownTotalAndSafeFallback() {
        assertEquals("1.0 / 4.0 MB · 25%", downloadProgressSummary(1L * 1024 * 1024, 4L * 1024 * 1024))
        assertEquals("2.0 MB", downloadProgressSummary(2L * 1024 * 1024, -1))
        assertEquals("4.0 / 4.0 MB · 100%", downloadProgressSummary(9L * 1024 * 1024, 4L * 1024 * 1024))
    }

    @Test fun qualityDowngradeUsesOfficialTierNames() {
        assertEquals("SQ · 无损品质不可用，已自动使用HQ · 高品质", qualityFallbackMessage("sq", "hq"))
        assertEquals(null, qualityFallbackMessage("standard", "128"))
        assertEquals(null, qualityFallbackMessage("hq", "320"))
    }

    @Test fun playbackSnapshotKeepsQueueDirectionAndReadsOldSnapshots() {
        val json = Json { ignoreUnknownKeys = true }
        val snapshot = PlaybackSnapshot(Track("a", "A"), listOf(Track("a", "A")), 12_345, queueReversed = true)
        assertEquals(true, json.decodeFromString<PlaybackSnapshot>(json.encodeToString(snapshot)).queueReversed)
        assertEquals(false, json.decodeFromString<PlaybackSnapshot>("""{"positionMs":99}""").queueReversed)
    }

    @Test fun playbackSnapshotOnlyBelongsToItsAccount() {
        val snapshot = PlaybackSnapshot(track = Track("a", "A"), ownerAccountId = "account-a")
        assertEquals(true, snapshot.belongsToAccount("account-a"))
        assertEquals(false, snapshot.belongsToAccount("account-b"))
        assertEquals(false, PlaybackSnapshot(track = Track("a", "A")).belongsToAccount("account-a"))
    }
}
