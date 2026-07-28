package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.qqFavoriteTrackWrite
import com.ronan.qmusicwatch.network.qqPlaylistTrackWrite
import com.ronan.qmusicwatch.network.qqWriteBusinessCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    @Test fun favoriteUsesLikedPlaylistDirectoryInsteadOfRejectedFavoriteContract() {
        val write = qqFavoriteTrackWrite(track, liked = true)

        assertEquals("music.musicasset.PlaylistDetailWrite", write.module)
        assertEquals("AddSonglist", write.method)
        assertEquals(201L, write.param.getValue("dirId").jsonPrimitive.long)
        val song = write.param.getValue("v_songInfo").jsonArray.single().jsonObject
        assertEquals(track.numericId, song.getValue("songId").jsonPrimitive.long)
        assertEquals(track.songType, song.getValue("songType").jsonPrimitive.content.toInt())
    }

    @Test fun removingFavoriteAndEditingPlaylistUseTheirOwnDirectories() {
        val favorite = qqFavoriteTrackWrite(track, liked = false)
        val playlist = qqPlaylistTrackWrite(directoryId = 5566L, track = track, add = false)

        assertEquals("DelSonglist", favorite.method)
        assertEquals(201L, favorite.param.getValue("dirId").jsonPrimitive.long)
        assertEquals("DelSonglist", playlist.method)
        assertEquals(5566L, playlist.param.getValue("dirId").jsonPrimitive.long)
    }

    @Test fun nestedWriteFailureIsNotTreatedAsSuccess() {
        assertEquals(80092, qqWriteBusinessCode(buildJsonObject { put("retCode", 80092) }))
        assertEquals(0, qqWriteBusinessCode(buildJsonObject { put("retcode", 0) }))
        assertEquals(null, qqWriteBusinessCode(buildJsonObject {}))
    }
}
