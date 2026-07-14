package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueTest {
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

    @Test fun selectedImportAddsOnlyCheckedTracksWithoutDuplicates() {
        val a = Track("a", "A"); val b = Track("b", "B"); val c = Track("c", "C")
        assertEquals(listOf("a", "c"), mergeSelectedQueue(listOf(a), listOf(a, b, c), setOf("a", "c")).map(Track::id))
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

    @Test fun profileCacheRefreshesOnlyWhenMissingWrongAccountUnknownOrExpired() {
        val now = 2_000_000_000_000L
        val fresh = com.ronan.qmusicwatch.model.CachedUserProfile("1", com.ronan.qmusicwatch.model.UserProfile(displayName = "Ronan", isVip = true, vipExpireAt = now / 1000 + 86_400), now - 100L * 24 * 60 * 60 * 1000)
        assertEquals(false, profileCacheNeedsRefresh(fresh, "1", now))
        assertEquals(true, profileCacheNeedsRefresh(fresh, "2", now))
        assertEquals(true, profileCacheNeedsRefresh(fresh.copy(profile = fresh.profile.copy(isVip = null)), "1", now))
        assertEquals(true, profileCacheNeedsRefresh(fresh.copy(profile = fresh.profile.copy(vipExpireAt = now / 1000)), "1", now))
    }

    @Test fun dailyRecommendationWrapsWithoutGrowingPastCount() {
        assertEquals(listOf(4, 5, 1), dailyBatch(listOf(1, 2, 3, 4, 5), 3, 3))
        assertEquals(listOf(1, 2), dailyBatch(listOf(1, 2), 0, 10))
    }

    @Test fun playbackSnapshotKeepsQueueDirectionAndReadsOldSnapshots() {
        val json = Json { ignoreUnknownKeys = true }
        val snapshot = PlaybackSnapshot(Track("a", "A"), listOf(Track("a", "A")), 12_345, queueReversed = true)
        assertEquals(true, json.decodeFromString<PlaybackSnapshot>(json.encodeToString(snapshot)).queueReversed)
        assertEquals(false, json.decodeFromString<PlaybackSnapshot>("""{"positionMs":99}""").queueReversed)
    }
}
