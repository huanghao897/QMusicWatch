package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.qqFavoriteComm
import com.ronan.qmusicwatch.network.qqFavoriteTrackWrite
import com.ronan.qmusicwatch.network.qqPlaylistTrackWrite
import com.ronan.qmusicwatch.network.qqWriteBusinessCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteWriteTest {
    private val track = Track(
        id = "00485V8K4InqbZ",
        title = "水星记",
        numericId = 107_192_078L,
        songType = 0,
    )

    @Test fun favoriteUsesTheDedicatedMidContract() {
        val write = qqFavoriteTrackWrite(track.copy(id = "  ${track.id}  "), liked = true)

        assertEquals("music.musicasset.SongFavWrite", write.module)
        assertEquals("AddSongFans", write.method)
        assertEquals(
            track.id,
            write.param.getValue("v_songMid").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test fun removingFavoriteAndEditingPlaylistKeepSeparateContracts() {
        val favorite = qqFavoriteTrackWrite(track, liked = false)
        val playlist = qqPlaylistTrackWrite(directoryId = 5566L, track = track, add = false)

        assertEquals("DelSongFans", favorite.method)
        assertEquals("DelSonglist", playlist.method)
        assertEquals("5566", playlist.param.getValue("dirId").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun favoriteRejectsTracksWithoutAUsableMid() {
        qqFavoriteTrackWrite(track.copy(id = " null "), liked = true)
    }

    @Test fun favoriteUsesTheRequiredClientContext() {
        val comm = qqFavoriteComm(buildJsonObject {
            put("ct", 24)
            put("cv", 4_747_474)
            put("uin", "12345")
        })

        assertEquals("20", comm.getValue("ct").jsonPrimitive.content)
        assertEquals("0", comm.getValue("cv").jsonPrimitive.content)
        assertEquals("12345", comm.getValue("uin").jsonPrimitive.content)
    }

    @Test fun nestedWriteFailureIsNotTreatedAsSuccess() {
        assertEquals(80092, qqWriteBusinessCode(buildJsonObject { put("retCode", 80092) }))
        assertEquals(0, qqWriteBusinessCode(buildJsonObject { put("retcode", 0) }))
        assertEquals(null, qqWriteBusinessCode(buildJsonObject {}))
    }
}
