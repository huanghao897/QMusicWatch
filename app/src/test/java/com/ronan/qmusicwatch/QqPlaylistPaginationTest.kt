package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QqPlaylistPaginationTest {
    private fun playlist(id: Int) = buildJsonObject {
        put("tid", id); put("dissname", if (id == 0) "我喜欢" else "歌单 $id"); put("songnum", 12)
    }
    private fun page(items: List<JsonElement>, total: Int? = null, more: Boolean? = null) = buildJsonObject {
        put("v_list", JsonArray(items))
        total?.let { put("total", it) }
        more?.let { put("hasmore", it) }
    }

    @Test fun filteredSystemPlaylistDoesNotStopAFullUpstreamPage() = runBlocking {
        val raw = (0..105).map(::playlist)
        val offsets = mutableListOf<Int>()
        val items = collectQqPlaylistPages { offset, size ->
            offsets += offset
            page(raw.drop(offset).take(size))
        }
        assertEquals(listOf(0, 100), offsets)
        assertEquals(105, items.size)
        assertEquals("105", items.last().id)
    }

    @Test fun serverPageCapUsesRawCountAndDeclaredTotal() = runBlocking {
        val raw = (1..137).map(::playlist)
        val offsets = mutableListOf<Int>()
        val items = collectQqPlaylistPages { offset, _ ->
            offsets += offset
            page(raw.drop(offset).take(30), total = 137)
        }
        assertEquals(listOf(0, 30, 60, 90, 120), offsets)
        assertEquals(137, items.size)
    }

    @Test fun duplicatesDoNotChangeTheUpstreamOffset() = runBlocking {
        val raw = (1..230).map { playlist(1 + it / 2) }
        val offsets = mutableListOf<Int>()
        val items = collectQqPlaylistPages { offset, size ->
            offsets += offset
            page(raw.drop(offset).take(size), more = offset < 200)
        }
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(116, items.size)
    }

    @Test fun allFilteredShortPagesContinueWhenHasMoreIsTrue() = runBlocking {
        val offsets = mutableListOf<Int>()
        val items = collectQqPlaylistPages { offset, _ ->
            offsets += offset
            page(listOf(if (offset == 0) playlist(0) else playlist(42)), more = offset == 0)
        }
        assertEquals(listOf(0, 1), offsets)
        assertEquals(listOf("42"), items.map { it.id })
    }

    @Test fun failuresDoNotReturnAPartialCollectionAsSuccess() {
        assertThrows(IOException::class.java) { runBlocking {
            collectQqPlaylistPages { offset, _ ->
                if (offset > 0) throw IOException("synthetic failure")
                page(listOf(playlist(42)), more = true)
            }
        } }
    }

    @Test fun emptyIncompleteAndRepeatedPagesAreReportedInsteadOfSilentlyTruncated() {
        assertThrows(QqResponseException::class.java) { runBlocking {
            collectQqPlaylistPages { _, _ -> page(emptyList(), more = true) }
        } }
        assertThrows(QqResponseException::class.java) { runBlocking {
            collectQqPlaylistPages { _, _ -> page(listOf(playlist(42)), more = true) }
        } }
        assertThrows(QqResponseException::class.java) { runBlocking {
            collectQqPlaylistPages(maxPages = 1) { _, _ -> page(listOf(playlist(42)), more = true) }
        } }
    }

    @Test fun missingSchemaIsNotAnEmptyLibraryButAnExplicitEmptyListIsValid() = runBlocking {
        assertThrows(QqResponseException::class.java) { parseQqPlaylistPage(buildJsonObject {}) }
        assertTrue(collectQqPlaylistPages { _, _ -> page(emptyList(), total = 0) }.isEmpty())
    }
}
