package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueTest {
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
