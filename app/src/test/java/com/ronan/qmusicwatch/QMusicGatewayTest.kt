package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.QqCredentialExpiredException
import com.ronan.qmusicwatch.network.QqHttpException
import com.ronan.qmusicwatch.network.QqResponseException
import com.ronan.qmusicwatch.network.isCurrentStreamGeneration
import com.ronan.qmusicwatch.network.isIdempotentQqReadMethod
import com.ronan.qmusicwatch.network.isRecoverableQqReadFailure
import com.ronan.qmusicwatch.network.qmusicAlbumArtworkUrl
import com.ronan.qmusicwatch.network.qmusicAvatarUrl
import com.ronan.qmusicwatch.network.qmusicSongArtworkUrl
import com.ronan.qmusicwatch.network.qqReadRetryDelayMs
import com.ronan.qmusicwatch.network.qqCredentialRefreshRequest
import com.ronan.qmusicwatch.network.requiresNewQrLogin
import com.ronan.qmusicwatch.network.resolveQqStreamUrl
import com.ronan.qmusicwatch.network.safeLocalOrArtworkUri
import com.ronan.qmusicwatch.network.safeLocalOrQqMediaUri
import com.ronan.qmusicwatch.network.shouldRefreshCredential
import com.ronan.qmusicwatch.network.shouldProbePlaybackCredential
import com.ronan.qmusicwatch.network.trustedQMusicArtworkUrl
import com.ronan.qmusicwatch.network.trustedQMusicImageUrl
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import com.ronan.qmusicwatch.network.validateRefreshedCookie
import java.io.IOException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QMusicGatewayTest {
    @Test fun artworkAndAvatarUseOfficialTencentHosts() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000albumMID123.jpg",
            qmusicAlbumArtworkUrl("albumMID123"),
        )
        assertEquals(
            "https://q1.qlogo.cn/g?b=qq&nk=12345&s=140",
            qmusicAvatarUrl("o12345"),
        )
        // A song MID is not an album MID, so fabricating a cover URL is unsafe.
        assertEquals("", qmusicSongArtworkUrl("00485V8K4InqbZ"))
        assertEquals("", qmusicAlbumArtworkUrl("../unsafe"))
        assertEquals("", qmusicAvatarUrl("not-a-uin"))
    }

    @Test fun onlyOfficialQqMusicAudioHostsAreTrusted() {
        val stream = "https://isure.stream.qqmusic.qq.com/C400song.m4a?vkey=short-lived"
        assertEquals(stream, trustedQMusicMediaUrl(stream))
        assertEquals(
            "https://dl.stream.qqmusic.qq.com/M500song.mp3?vkey=key",
            resolveQqStreamUrl(
                "M500song.mp3?vkey=key",
                listOf("https://dl.stream.qqmusic.qq.com/"),
            ),
        )
        assertEquals("", trustedQMusicMediaUrl("https://heyboxlite.xyz/api/qmusic-watch/gateway/media/token/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("https://203.160.55.168/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("https://example.com/song.mp3"))
        assertEquals("", resolveQqStreamUrl("https://example.com/song.mp3", emptyList()))
    }

    @Test fun localFilesAndOfficialTencentImagesRemainUsable() {
        val local = "file:///data/user/0/com.ronan.qmusicwatch/files/song"
        val artwork = qmusicAlbumArtworkUrl("albumMID123")
        val avatar = qmusicAvatarUrl("12345")
        assertEquals(local, safeLocalOrQqMediaUri(local))
        assertEquals(local, safeLocalOrArtworkUri(local))
        assertEquals(artwork, trustedQMusicArtworkUrl(artwork))
        assertEquals(artwork, trustedQMusicImageUrl(artwork))
        assertEquals(avatar, trustedQMusicImageUrl(avatar))
        assertEquals(artwork, safeLocalOrArtworkUri(artwork))
        assertEquals("", safeLocalOrQqMediaUri(artwork))
        assertEquals("", trustedQMusicImageUrl("https://example.com/cover.jpg"))
        assertEquals("", safeLocalOrQqMediaUri("https://example.com/song.mp3"))
    }

    @Test fun refreshedCookieMustKeepTheSameAccountAndPlaybackKey() {
        val stale = "qqmusic_uin=12345; qm_keyst=old"
        val refreshed = "qqmusic_uin=12345; qm_keyst=new; refresh_token=rotated"
        assertEquals(refreshed, validateRefreshedCookie(stale, refreshed))
        assertThrows(IllegalArgumentException::class.java) {
            validateRefreshedCookie(stale, "qqmusic_uin=67890; qm_keyst=new")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateRefreshedCookie(stale, "qqmusic_uin=12345")
        }
    }

    @Test fun onlyRealCredentialFailuresTriggerARefresh() {
        assertTrue(shouldRefreshCredential("music.UserInfo.userInfoServer", "GetLoginUserInfo", 1000))
        assertTrue(shouldRefreshCredential("vkey.GetVkeyServer", "CgiGetVkey", 104400))
        assertFalse(shouldRefreshCredential("music.UserInfo.userInfoServer", "GetUserInfo", 1000))
        assertFalse(shouldRefreshCredential("music.radioProxy.MbTrackRadioSvr", "get_radio_track", 1000))
        assertFalse(shouldRefreshCredential("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo", 80050))
        assertFalse(shouldRefreshCredential("music.UserInfo.userInfoServer", "GetLoginUserInfo", 0))
    }

    @Test fun onlyAnExplicitCredentialExpiryRequiresNewQrLogin() {
        assertTrue(requiresNewQrLogin(QqCredentialExpiredException("expired")))
        assertFalse(requiresNewQrLogin(QqHttpException(503, "retry")))
        assertFalse(requiresNewQrLogin(IOException("offline")))
    }

    @Test fun transientDirectFailuresRetryOnlySafeReadMethods() {
        assertTrue(isIdempotentQqReadMethod("CgiGetDiss"))
        assertTrue(isIdempotentQqReadMethod("GetPlaylistByUin"))
        assertTrue(isIdempotentQqReadMethod("UrlGetVkey"))
        assertTrue(isIdempotentQqReadMethod("DoSearchForQQMusicDesktop"))
        assertFalse(isIdempotentQqReadMethod("AddSonglist"))
        assertFalse(isIdempotentQqReadMethod("DeleteSongFav"))

        assertTrue(isRecoverableQqReadFailure(QqResponseException("invalid json")))
        assertTrue(isRecoverableQqReadFailure(QqHttpException(503, "retry")))
        assertTrue(isRecoverableQqReadFailure(QqHttpException(429, "retry", 1_500L)))
        assertTrue(isRecoverableQqReadFailure(IOException("connection reset")))
        assertFalse(isRecoverableQqReadFailure(QqCredentialExpiredException("expired")))
        assertFalse(isRecoverableQqReadFailure(IllegalStateException("business failure")))

        assertEquals(1_500L, qqReadRetryDelayMs(QqHttpException(429, "retry", 1_500L)))
        assertEquals(500L, qqReadRetryDelayMs(QqHttpException(429, "retry")))
        assertEquals(250L, qqReadRetryDelayMs(QqResponseException("invalid json")))
    }

    @Test fun invalidatedStreamRequestsCannotReturnTheirOldResult() {
        assertTrue(isCurrentStreamGeneration(captured = 4L, current = 4L))
        assertFalse(isCurrentStreamGeneration(captured = 4L, current = 5L))
        assertFalse(isCurrentStreamGeneration(captured = 4L, current = null))
    }

    @Test fun missingFirstStreamResultProbesCredentialsForEveryTrackTier() {
        assertTrue(shouldProbePlaybackCredential(qualityIndex = 0, hasFallback = false, allowRecovery = true))
        assertFalse(shouldProbePlaybackCredential(qualityIndex = 1, hasFallback = false, allowRecovery = true))
        assertFalse(shouldProbePlaybackCredential(qualityIndex = 0, hasFallback = true, allowRecovery = true))
        assertFalse(shouldProbePlaybackCredential(qualityIndex = 0, hasFallback = false, allowRecovery = false))
    }

    @Test fun qqCredentialRefreshUsesDesktopLoginContract() {
        val request = qqCredentialRefreshRequest(
            musicId = "12345",
            musicKey = "music-key",
            openId = "open-id",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            refreshKey = "refresh-key",
            unionId = "union-id",
            expiredAt = 1_800_000_000L,
            guid = "1234567890",
            wid = "9876543210",
            deviceName = "QMusicWatch-TEST",
        )
        assertEquals(100497308, request.param["appid"]?.jsonPrimitive?.int)
        assertEquals("Windows", request.param["deviceType"]?.jsonPrimitive?.content)
        assertEquals("access-token", request.param["access_token"]?.jsonPrimitive?.content)
        assertEquals(0, request.param["onlyNeedAccessToken"]?.jsonPrimitive?.int)
        assertEquals("19", request.comm["ct"]?.jsonPrimitive?.content)
        assertEquals(2, request.comm["tmeLoginType"]?.jsonPrimitive?.int)
        assertEquals("9876543210", request.comm["wid"]?.jsonPrimitive?.content)
    }
}
