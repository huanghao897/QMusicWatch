package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.data.mergeRecent
import com.ronan.qmusicwatch.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentMergerTest {
    @Test fun newestWinsAndUntimedCloudAppends() {
        val merged = mergeRecent(listOf(Track("a", "A", playedAt = 20)), listOf(Track("a", "old", playedAt = 10), Track("b", "B")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals("A", merged.first().title)
    }
}

