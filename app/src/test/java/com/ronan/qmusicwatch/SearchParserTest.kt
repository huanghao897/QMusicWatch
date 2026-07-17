package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseSearchTrack
import com.ronan.qmusicwatch.network.nextSearchCursor
import com.ronan.qmusicwatch.network.normalizeHttpsUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchParserTest {
    @Test fun paginationUsesRawPageSizeInsteadOfSuccessfullyParsedCount() {
        assertEquals("3", nextSearchCursor(page = 2, rawItemCount = 20))
        assertEquals(null, nextSearchCursor(page = 2, rawItemCount = 19))
    }

    @Test fun protocolRelativeArtworkUrlsBecomeHttps() {
        assertEquals("https://img.qq.com/a.jpg", normalizeHttpsUrl("//img.qq.com/a.jpg"))
        assertEquals("https://img.qq.com/a.jpg", normalizeHttpsUrl("http://img.qq.com/a.jpg"))
    }
    @Test fun parsesFullSearchSongShape() {
        val item = Json.parseToJsonElement("""{"songmid":"001","songname":"搁浅","songid":12,"albummid":"alb","albumname":"七里香","size128":10,"size320":20,"singer":[{"name":"周杰伦"}],"pay":{"payplay":1}}""").jsonObject
        val track = parseSearchTrack(item)!!
        assertEquals("001", track.id)
        assertEquals(listOf("周杰伦"), track.artists)
        assertEquals(listOf("128", "320"), track.qualities)
        assertEquals(true, track.requiresVip)
    }

    @Test fun readsModernNestedFileQualityFields() {
        val item = Json.parseToJsonElement("""{"mid":"song-mid","title":"Song","id":42,"type":0,"isonly":1,"singer":[{"name":"Singer"}],"album":{"title":"Album","mid":"album-mid"},"file":{"media_mid":"media-mid","size_128mp3":100,"size_320mp3":200},"pay":{"pay_play":1}}""").jsonObject
        val track = parseSearchTrack(item)!!
        assertEquals(listOf("128", "320"), track.qualities)
        assertEquals("media-mid", track.mediaMid)
        assertEquals(42L, track.numericId)
        assertEquals("Album", track.album)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000album-mid.jpg", track.artworkUrl)
        assertEquals(true, track.requiresVip)
    }
}
