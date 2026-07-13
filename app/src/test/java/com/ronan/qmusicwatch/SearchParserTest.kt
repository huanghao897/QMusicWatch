package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseSearchTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchParserTest {
    @Test fun parsesFullSearchSongShape() {
        val item = Json.parseToJsonElement("""{"songmid":"001","songname":"搁浅","songid":12,"albummid":"alb","albumname":"七里香","size128":10,"size320":20,"singer":[{"name":"周杰伦"}],"pay":{"payplay":1}}""").jsonObject
        val track = parseSearchTrack(item)!!
        assertEquals("001", track.id)
        assertEquals(listOf("周杰伦"), track.artists)
        assertEquals(listOf("128", "320"), track.qualities)
        assertEquals(true, track.requiresVip)
    }
}
