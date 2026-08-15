package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.qqFavoriteComm
import com.ronan.qmusicwatch.network.qqFavoritePlaylistFallback
import com.ronan.qmusicwatch.network.qqFavoriteTrackWrite
import com.ronan.qmusicwatch.network.qqMusicuPayload
import com.ronan.qmusicwatch.network.qqPlaylistTrackWrite
import com.ronan.qmusicwatch.network.qqWriteBusinessCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteWriteTest {
    private val track = Track(
        id = "00485V8K4InqbZ",
        title = "水星记",
        numericId = 107_192_078L,
        songType = 7,
    )

    @Test fun favoriteUsesTheOfficialScalarMidAndIdContract() {
        val write = qqFavoriteTrackWrite(track.copy(id = "  ${track.id}  "), liked = true)

        assertEquals("music.musicasset.SongFavWrite", write.module)
        assertEquals("AddSongFav", write.method)
        assertEquals(track.id, write.param.getValue("v_songMid").jsonPrimitive.content)
        assertEquals(track.numericId.toString(), write.param.getValue("v_songId").jsonPrimitive.content)
        assertEquals(setOf("v_songMid", "v_songId"), write.param.keys)
    }

    @Test fun removingFavoriteAndEditingPlaylistKeepSeparateContracts() {
        val favorite = qqFavoriteTrackWrite(track, liked = false)
        val playlist = qqPlaylistTrackWrite(directoryId = 5566L, track = track, add = false)

        assertEquals("DeleteSongFav", favorite.method)
        assertEquals("DelSonglist", playlist.method)
        assertEquals("5566", playlist.param.getValue("dirId").jsonPrimitive.content)
    }

    @Test fun rejectedFavoriteCanFallBackToTheSystemLikedDirectory() {
        val fallback = qqFavoritePlaylistFallback(track, liked = true)
        val songInfo = fallback.param.getValue("v_songInfo").jsonArray.single().jsonObject

        assertEquals("music.musicasset.PlaylistDetailWrite", fallback.module)
        assertEquals("AddSonglist", fallback.method)
        assertEquals("201", fallback.param.getValue("dirId").jsonPrimitive.content)
        assertEquals("0", songInfo.getValue("songType").jsonPrimitive.content)
        assertEquals(track.numericId.toString(), songInfo.getValue("songId").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun favoriteRejectsTracksWithoutAUsableMid() {
        qqFavoriteTrackWrite(track.copy(id = " null "), liked = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun favoriteRejectsTracksWithoutANumericId() {
        qqFavoriteTrackWrite(track.copy(numericId = 0), liked = true)
    }

    @Test fun favoriteUsesTheRequiredAndroidClientContext() {
        val comm = qqFavoriteComm("12345")

        assertEquals("19", comm.getValue("ct").jsonPrimitive.content)
        assertEquals("1845", comm.getValue("cv").jsonPrimitive.content)
        assertEquals("12345", comm.getValue("uin").jsonPrimitive.content)
    }

    @Test fun musicuPayloadUsesTheSingleReqZeroEnvelope() {
        val write = qqFavoriteTrackWrite(track, liked = true)
        val payload = qqMusicuPayload(qqFavoriteComm("12345"), write.module, write.method, write.param)
        val request = payload.getValue("req_0").jsonObject

        assertEquals(setOf("comm", "req_0"), payload.keys)
        assertEquals(write.module, request.getValue("module").jsonPrimitive.content)
        assertEquals(write.method, request.getValue("method").jsonPrimitive.content)
        assertEquals(write.param, request.getValue("param").jsonObject)
    }

    @Test fun nestedWriteFailureIsNotTreatedAsSuccess() {
        assertEquals(80092, qqWriteBusinessCode(buildJsonObject { put("retCode", 80092) }))
        assertEquals(0, qqWriteBusinessCode(buildJsonObject { put("retcode", 0) }))
        assertEquals(null, qqWriteBusinessCode(buildJsonObject {}))
    }
}
