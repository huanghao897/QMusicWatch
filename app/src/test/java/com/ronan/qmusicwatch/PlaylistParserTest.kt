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
        assertEquals(true, item.owned)
    }

    @Test fun separatesCreatedAndCollectedPlaylists() {
        val root = Json.parseToJsonElement("""{"data":{"v_playlist":[{"dirId":1,"dirName":"我的","tid":11}],"v_collect":[{"dirId":2,"dirName":"收藏","tid":22}]}}""")
        val items = parseAccountPlaylists(root).associateBy { it.title }
        assertEquals(true, items["我的"]?.owned)
        assertEquals(false, items["收藏"]?.owned)
    }
}
