package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseAccountPlaylists
import com.ronan.qmusicwatch.network.playlistDirectoryNumber
import com.ronan.qmusicwatch.network.parseFavoritePlaylists
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistParserTest {
    @Test fun validatesWritablePlaylistDirectoryIds() {
        assertEquals(201L, playlistDirectoryNumber("201"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { playlistDirectoryNumber("") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { playlistDirectoryNumber("not-a-number") }
    }
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

    @Test fun parsesDedicatedFavoritePlaylistResponseAsReadOnly() {
        val root = Json.parseToJsonElement("""{"v_list":[{"tid":"9988","dissname":"收藏的歌单","songnum":42,"picurl":"//img.qq.com/list.jpg"}]}""")
        val item = parseFavoritePlaylists(root).single()
        assertEquals("9988", item.id)
        assertEquals("收藏的歌单", item.title)
        assertEquals(42, item.trackCount)
        assertEquals(false, item.owned)
        assertEquals("https://img.qq.com/list.jpg", item.artworkUrl)
    }
}
