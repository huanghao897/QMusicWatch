package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.CachedAccountSnapshot
import com.ronan.qmusicwatch.model.CachedAccountSnapshots
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSnapshotTest {
    @Test fun snapshotsStayIsolatedByAccount() {
        val first = upsertAccountSnapshot(CachedAccountSnapshots(), CachedAccountSnapshot("a", updatedAt = 1))
        val second = upsertAccountSnapshot(first, CachedAccountSnapshot("b", updatedAt = 2))
        val replaced = upsertAccountSnapshot(second, CachedAccountSnapshot("a", updatedAt = 3))
        assertEquals(listOf("a", "b"), replaced.items.map { it.accountId })
        assertEquals(3, replaced.items.first().updatedAt)
    }
}
