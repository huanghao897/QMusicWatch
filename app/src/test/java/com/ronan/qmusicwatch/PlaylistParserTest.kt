package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseAccountPlaylists
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistParserTest {
    @Test fun parsesAccountPlaylistIdsWithoutMixingTidAndDirId() {
        val root = Json.parseToJsonElement("""{"data":{"v_playlist":[{"dirId":202,"dirName":"通勤","tid":987654,"songNum":12,"picUrl":"http://img"}]}}""")
        val item = parseAccountPlaylists(root).single()
        assertEquals("987654", item.id)
        assertEquals("202", item.directoryId)
        assertEquals("通勤", item.title)
        assertEquals(12, item.trackCount)
        assertEquals("https://img", item.artworkUrl)
    }
}
