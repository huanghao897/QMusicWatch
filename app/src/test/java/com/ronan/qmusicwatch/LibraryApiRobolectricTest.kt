package com.ronan.qmusicwatch

import android.app.Application
import com.ronan.qmusicwatch.model.MusicCollection
import com.ronan.qmusicwatch.model.CollectionDetail
import com.ronan.qmusicwatch.network.ApiClient
import com.ronan.qmusicwatch.network.QqBusinessException
import com.ronan.qmusicwatch.network.QqResponseException
import com.ronan.qmusicwatch.ui.collectionCountLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryApiRobolectricTest {
    private fun client(includeEncryptedId: Boolean = true, response: (String) -> Pair<Int, String>): ApiClient {
        val api = ApiClient(RuntimeEnvironment.getApplication(), cookie = {
            "qqmusic_uin=12345; qm_keyst=fixture-key" + if (includeEncryptedId) "; euin=fixture-uin" else ""
        })
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).jsonObject
            val key = body.keys.single { it != "comm" }
            val method = body[key]!!.jsonObject["method"]!!.jsonPrimitive.content
            val (code, data) = response(method)
            val payload = """{"code":0,"$key":{"code":$code,"data":$data}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture only")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(api, http)
        return api
    }

    @Test fun favoriteFailureFailsTheLibraryInsteadOfReturningAnEmptyFavoriteSection() {
        val api = client { method -> when (method) {
            "GetPlaylistByUin" -> 0 to """{"v_playlist":[]}"""
            "CgiGetPlaylistFavInfo" -> 123 to "{}"
            else -> 0 to """{"songlist":[]}"""
        } }
        val error = assertThrows(QqBusinessException::class.java) { runBlocking { api.library() } }
        assertEquals(123, error.businessCode)
    }

    @Test fun legacyFavoritesMissingTheirListAreNotAcceptedAsAnEmptyLibrary() {
        val api = client(includeEncryptedId = false) { method ->
            0 to when (method) {
                "GetPlaylistByUin" -> """{"v_playlist":[]}"""
                "GetPlaylistFavInfo" -> "{}"
                else -> """{"songlist":[]}"""
            }
        }
        assertThrows(QqResponseException::class.java) { runBlocking { api.library() } }
    }

    @Test fun legacyCompatibilityFallbackStillAcceptsAnExplicitEmptyList() = runBlocking {
        val api = client { method -> when (method) {
            "CgiGetPlaylistFavInfo" -> 80050 to "{}"
            "GetPlaylistFavInfo" -> 0 to """{"v_list":[]}"""
            "GetPlaylistByUin" -> 0 to """{"v_playlist":[]}"""
            else -> 0 to """{"songlist":[]}"""
        } }
        val library = api.library()
        assertTrue(library.playlists.isEmpty())
        assertTrue(library.liked.isEmpty())
    }

    @Test fun detailRetainsCustomPlaylistCoverAndDeclaredCountEvenIfATrackCannotBeParsed() = runBlocking {
        val cover = "https://y.gtimg.cn/music/fixture-custom-cover.jpg"
        val api = client { 0 to """{"dirinfo":{"title":"自定义歌单"},"songlist":[null],"total_song_num":1}""" }
        val detail = api.collection("playlist", MusicCollection("123456", "旧标题", cover, trackCount = 99, owned = false))
        assertEquals("自定义歌单", detail.title)
        assertEquals(cover, detail.artworkUrl)
        assertEquals(1, detail.trackCount)
        assertTrue(detail.tracks.isEmpty())
    }

    @Test fun oldSerializedDetailsRemainReadableAndUnknownCountIsNeverDisplayedAsZero() {
        val detail = Json.decodeFromString<CollectionDetail>("""{"title":"旧歌单","tracks":[]}""")
        assertEquals("", detail.artworkUrl)
        assertEquals(-1, detail.trackCount)
        assertEquals("点击查看", collectionCountLabel(-1))
        assertEquals("0 首", collectionCountLabel(0))
        assertEquals("400 首", collectionCountLabel(400))
    }
}
