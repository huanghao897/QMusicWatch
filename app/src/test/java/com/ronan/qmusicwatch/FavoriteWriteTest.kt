package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.qqFavoriteTrackWrite
import com.ronan.qmusicwatch.network.qqPlaylistTrackWrite
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteWriteTest {
    private val track = Track(
        id = "00485V8K4InqbZ",
        title = "水星记",
        numericId = 107_192_078L,
        songType = 0,
    )

    @Test fun favoriteUsesTheFixedLikedPlaylistInsteadOfTheRejectedSongFavContract() {
        val write = qqFavoriteTrackWrite(track, liked = true)

        assertEquals("music.musicasset.PlaylistDetailWrite", write.module)
        assertEquals("AddSonglist", write.method)
        assertEquals(201L, write.param.getValue("dirId").jsonPrimitive.long)
        assertEquals(0, write.param.getValue("tid").jsonPrimitive.int)
        assertTrue(write.param.getValue("bFmtUtf8").jsonPrimitive.boolean)
        val song = write.param.getValue("v_songInfo").jsonArray.single().jsonObject
        assertEquals(track.numericId, song.getValue("songId").jsonPrimitive.long)
        assertEquals(track.songType, song.getValue("songType").jsonPrimitive.int)
    }

    @Test fun removingFavoriteAndEditingAPlaylistShareTheCurrentWriteContract() {
        val favorite = qqFavoriteTrackWrite(track, liked = false)
        val playlist = qqPlaylistTrackWrite(directoryId = 5566L, track = track, add = false)

        assertEquals("DelSonglist", favorite.method)
        assertEquals("DelSonglist", playlist.method)
        assertEquals(201L, favorite.param.getValue("dirId").jsonPrimitive.long)
        assertEquals(5566L, playlist.param.getValue("dirId").jsonPrimitive.long)
    }
}
