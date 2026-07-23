package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.parseSearchTrack
import com.ronan.qmusicwatch.network.isUsableQqSongMid
import com.ronan.qmusicwatch.network.nextSearchCursor
import com.ronan.qmusicwatch.network.normalizeHttpsUrl
import com.ronan.qmusicwatch.network.qqStreamFileName
import com.ronan.qmusicwatch.network.inferQqStreamQuality
import com.ronan.qmusicwatch.network.higherQualityStream
import com.ronan.qmusicwatch.model.QUALITY_HI_RES
import com.ronan.qmusicwatch.model.QUALITY_HQ
import com.ronan.qmusicwatch.model.QUALITY_SQ
import com.ronan.qmusicwatch.model.QUALITY_STANDARD
import com.ronan.qmusicwatch.model.StreamData
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
        assertEquals(listOf(QUALITY_STANDARD, QUALITY_HQ), track.qualities)
        assertEquals(true, track.requiresVip)
    }

    @Test fun readsModernNestedFileQualityFields() {
        val item = Json.parseToJsonElement("""{"mid":"song-mid","title":"Song","id":42,"type":0,"isonly":1,"singer":[{"name":"Singer"}],"album":{"title":"Album","mid":"album-mid"},"file":{"media_mid":"media-mid","size_128mp3":100,"size_320mp3":200,"size_flac":300,"size_hires":400},"pay":{"pay_play":1}}""").jsonObject
        val track = parseSearchTrack(item)!!
        assertEquals(listOf(QUALITY_STANDARD, QUALITY_HQ, QUALITY_SQ, QUALITY_HI_RES), track.qualities)
        assertEquals("media-mid", track.mediaMid)
        assertEquals(42L, track.numericId)
        assertEquals("Album", track.album)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000album-mid.jpg", track.artworkUrl)
        assertEquals(true, track.requiresVip)
    }

    @Test fun placeholderSongMidFallsBackToModernMid() {
        val item = Json.parseToJsonElement("""{"songmid":"0","mid":"001ValidMid001","songname":"Song","singer":[{"name":"Singer"}]}""").jsonObject
        assertEquals("001ValidMid001", parseSearchTrack(item)?.id)
    }

    @Test fun placeholderSongMidCanRecoverFromGroupedResult() {
        val item = Json.parseToJsonElement("""{"songmid":"0","songname":"Container","grp":[{"songmid":"002GroupedMid1","songname":"Song","singer":[{"name":"Singer"}]}]}""").jsonObject
        assertEquals("002GroupedMid1", parseSearchTrack(item)?.id)
    }

    @Test fun unresolvedPlaceholderSongMidIsRejected() {
        val item = Json.parseToJsonElement("""{"songmid":"0","songname":"Broken","singer":[{"name":"Singer"}]}""").jsonObject
        assertEquals(null, parseSearchTrack(item))
        assertEquals(false, isUsableQqSongMid("0"))
        assertEquals(false, isUsableQqSongMid("0000"))
        assertEquals(false, isUsableQqSongMid("null"))
    }

    @Test fun verifiedDirectFormatsUseStableQqFileNames() {
        assertEquals("M500media-mid.mp3", qqStreamFileName(QUALITY_STANDARD, "media-mid"))
        assertEquals("M800media-mid.mp3", qqStreamFileName(QUALITY_HQ, "media-mid"))
        assertEquals("F000media-mid.flac", qqStreamFileName(QUALITY_SQ, "media-mid"))
    }

    @Test fun returnedFileNameWinsOverTheRequestedQualityLabel() {
        assertEquals(QUALITY_STANDARD, inferQqStreamQuality("C400media-mid.m4a?vkey=x", "purl"))
        assertEquals(QUALITY_HQ, inferQqStreamQuality("M800media-mid.mp3?vkey=x", "purl"))
        assertEquals(QUALITY_SQ, inferQqStreamQuality("F000media-mid.flac?vkey=x", "purl"))
        assertEquals(QUALITY_STANDARD, inferQqStreamQuality("https://cdn.example/audio", "opi128kurl"))
        assertEquals(QUALITY_STANDARD, inferQqStreamQuality("https://cdn.example/opaque", "purl"))
    }

    @Test fun laterLowerTierResponseCannotReplaceAWorkingHigherTierFallback() {
        val hq = StreamData("https://cdn.example/hq", QUALITY_HQ, 2_000)
        val standard = StreamData("https://cdn.example/standard", QUALITY_STANDARD, 2_000)
        assertEquals(hq, higherQualityStream(null, hq))
        assertEquals(hq, higherQualityStream(hq, standard))
        assertEquals(hq, higherQualityStream(standard, hq))
    }
}
