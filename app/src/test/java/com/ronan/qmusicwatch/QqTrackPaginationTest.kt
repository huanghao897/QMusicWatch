package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.QqResponseException
import com.ronan.qmusicwatch.network.collectQqTrackPages
import com.ronan.qmusicwatch.network.parseQqTrackPage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QqTrackPaginationTest {
    private fun rawTrack(id: String) = buildJsonObject { put("mid", id) }
    private fun parseTracks(root: JsonElement): List<Track> = (root as JsonArray)
        .mapNotNull { (it as? JsonObject)?.get("mid")?.jsonPrimitive?.content }
        .filter(String::isNotBlank).distinct().map { Track(it, it) }

    private fun page(items: List<JsonElement>, total: Int? = null, more: Int? = null) = buildJsonObject {
        put("songlist", JsonArray(items))
        if (total != null) put("total_song_num", total)
        if (more != null) put("hasmore", more)
    }

    @Test fun firstPageWithOnlyNinetyParsedSongsStillLoadsAllFourHundred() = runBlocking {
        val raw = (0 until 410).map { if (it < 10) JsonNull else rawTrack("song-$it") }
        val offsets = mutableListOf<Int>()
        val tracks = collectQqTrackPages(fetchPage = { offset, size ->
            offsets += offset
            page(raw.drop(offset).take(size), total = raw.size)
        }, parseTracks = ::parseTracks)
        assertEquals(listOf(0, 100, 200, 300, 400), offsets)
        assertEquals(400, tracks.size)
        assertEquals("song-409", tracks.last().id)
    }

    @Test fun duplicateEntriesDoNotStopOrChangeTheUpstreamOffset() = runBlocking {
        val raw = (0 until 230).map { rawTrack("song-${it / 2}") }
        val offsets = mutableListOf<Int>()
        val tracks = collectQqTrackPages(fetchPage = { offset, size ->
            offsets += offset
            page(raw.drop(offset).take(size))
        }, parseTracks = ::parseTracks)
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(115, tracks.size)
    }

    @Test fun serverSidePageCapUsesReturnedCountAndTotalNotRequestedSize() = runBlocking {
        val raw = (0 until 437).map { rawTrack("song-$it") }
        val offsets = mutableListOf<Int>()
        val tracks = collectQqTrackPages(fetchPage = { offset, _ ->
            offsets += offset
            page(raw.drop(offset).take(30), total = raw.size)
        }, parseTracks = ::parseTracks)
        assertEquals(437, tracks.size)
        assertEquals((0..420 step 30).toList(), offsets)
    }

    @Test fun anEntirePageOfUnavailableEntriesDoesNotHideLaterSongs() = runBlocking {
        val tracks = collectQqTrackPages(fetchPage = { offset, _ ->
            if (offset < 200) page(List(100) { JsonNull }, total = 201)
            else page(listOf(rawTrack("last-song")), total = 201)
        }, parseTracks = ::parseTracks)
        assertEquals(listOf("last-song"), tracks.map(Track::id))
    }

    @Test fun hasMoreAllowsShortPagesWithoutTotal() = runBlocking {
        val offsets = mutableListOf<Int>()
        val tracks = collectQqTrackPages(fetchPage = { offset, _ ->
            offsets += offset
            page(listOf(rawTrack("song-$offset")), more = if (offset < 3) 1 else 0)
        }, parseTracks = ::parseTracks)
        assertEquals(listOf(0, 1, 2, 3), offsets)
        assertEquals(4, tracks.size)
    }

    @Test fun parsesAlbumAndArtistWrappersWithoutCountingNestedSingers() {
        val data = Json.parseToJsonElement("""{"songList":[{"songInfo":{"singer":[{},{}]}}],"totalNum":40}""").jsonObject
        val page = parseQqTrackPage(data)
        assertEquals(1, page.items.size)
        assertEquals(40, page.total)
        assertEquals(1, page.nextOffset(0, 100))
    }

    @Test fun declaredConsumedSlotsPreventSkippingOrStoppingOnFilteredPages() {
        val data = Json.parseToJsonElement("""{"songlist":[{}],"songlist_size":100,"total_song_num":437,"hasmore":0}""").jsonObject
        assertEquals(100, parseQqTrackPage(data).nextOffset(0, 100))
        assertNull(parseQqTrackPage(data).nextOffset(400, 100))
    }

    @Test fun supportsLibrariesLargerThanTheOldTwentyPageLimit() = runBlocking {
        val tracks = collectQqTrackPages(fetchPage = { offset, size ->
            page((offset until minOf(offset + size, 2101)).map { rawTrack("song-$it") }, total = 2101)
        }, parseTracks = ::parseTracks)
        assertEquals(2101, tracks.size)
    }

    @Test fun repeatedPageIsAnErrorInsteadOfASilentlyTruncatedLibrary() {
        assertThrows(QqResponseException::class.java) {
            runBlocking {
                collectQqTrackPages(fetchPage = { _, _ ->
                    page(listOf(rawTrack("same-song")), total = 400)
                }, parseTracks = ::parseTracks)
            }
        }
    }

    @Test fun missingOrPrematurelyEmptyListDoesNotReplaceCachedLibrary() {
        assertThrows(QqResponseException::class.java) { parseQqTrackPage(JsonObject(emptyMap())) }
        assertThrows(QqResponseException::class.java) {
            runBlocking {
                collectQqTrackPages(fetchPage = { _, _ -> page(emptyList(), total = 400) }, parseTracks = ::parseTracks)
            }
        }
    }
}
