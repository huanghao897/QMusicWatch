package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.QMusicGatewayException
import com.ronan.qmusicwatch.network.QMusicGatewayResponseException
import com.ronan.qmusicwatch.network.isCurrentStreamGeneration
import com.ronan.qmusicwatch.network.isIdempotentQqReadMethod
import com.ronan.qmusicwatch.network.isRecoverableGatewayReadFailure
import com.ronan.qmusicwatch.network.gatewayReadRetryDelayMs
import com.ronan.qmusicwatch.network.qmusicAlbumArtworkUrl
import com.ronan.qmusicwatch.network.qmusicAvatarUrl
import com.ronan.qmusicwatch.network.qmusicSongArtworkUrl
import com.ronan.qmusicwatch.network.requiresNewQrLogin
import com.ronan.qmusicwatch.network.safeLocalOrGatewayUri
import com.ronan.qmusicwatch.network.sessionNeedsGatewayCredentialRefresh
import com.ronan.qmusicwatch.network.shouldRefreshCredential
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import com.ronan.qmusicwatch.network.validateRefreshedCookie
import com.ronan.qmusicwatch.model.SessionTokens
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QMusicGatewayTest {
    @Test fun stableMediaUrlsUseTheNewServer() {
        assertEquals(
            "https://heyboxlite.xyz/api/qmusic-watch/gateway/artwork/album/albumMID123.jpg",
            qmusicAlbumArtworkUrl("albumMID123"),
        )
        assertEquals(
            "https://heyboxlite.xyz/api/qmusic-watch/gateway/avatar/qq/12345.jpg",
            qmusicAvatarUrl("o12345"),
        )
        assertEquals(
            "https://heyboxlite.xyz/api/qmusic-watch/gateway/artwork/album/QMWTRACK00485V8K4InqbZ.jpg",
            qmusicSongArtworkUrl("00485V8K4InqbZ"),
        )
        assertEquals("", qmusicAlbumArtworkUrl("../unsafe"))
        assertEquals("", qmusicSongArtworkUrl("../unsafe"))
        assertEquals("", qmusicAvatarUrl("not-a-uin"))
    }

    @Test fun onlyIssuedGatewayMediaPathsAreTrusted() {
        val issued = "https://heyboxlite.xyz/api/qmusic-watch/gateway/media/${"t".repeat(32)}/M500song.mp3"
        assertEquals(issued, trustedQMusicMediaUrl(issued))
        assertEquals("", trustedQMusicMediaUrl("https://203.160.55.168/api/qmusic-watch/gateway/media/${"t".repeat(32)}/M500song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("https://8.138.134.236/api/qmusic-watch/gateway/media/${"t".repeat(32)}/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("https://heyboxlite.xyz/api/qmusic-watch/gateway/media/short/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("$issued?redirect=https://example.com"))
        assertEquals("", trustedQMusicMediaUrl("https://isure.stream.qqmusic.qq.com/M500song.mp3"))
    }

    @Test fun localFilesRemainUsableButOtherRemoteHostsAreRejected() {
        assertEquals("file:///data/user/0/com.ronan.qmusicwatch/files/song", safeLocalOrGatewayUri("file:///data/user/0/com.ronan.qmusicwatch/files/song"))
        assertEquals("", safeLocalOrGatewayUri("https://example.com/song.mp3"))
    }

    @Test fun oldGatewaySessionsNeedOneCredentialMigration() {
        val old = SessionTokens(
            accountId = "12345",
            provider = "qq",
            upstreamCookie = "qqmusic_uin=12345; qm_keyst=old",
        )
        assertTrue(sessionNeedsGatewayCredentialRefresh(old))
        assertTrue(sessionNeedsGatewayCredentialRefresh(old.copy(gatewayHost = "203.160.55.168")))
        assertFalse(sessionNeedsGatewayCredentialRefresh(old.copy(gatewayHost = "heyboxlite.xyz")))
        assertFalse(sessionNeedsGatewayCredentialRefresh(null))
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

    @Test fun onlyAnExplicitGatewayReloginErrorClearsTheSession() {
        assertTrue(requiresNewQrLogin(QMusicGatewayException(409, "RELOGIN_REQUIRED", "relogin")))
        assertFalse(requiresNewQrLogin(QMusicGatewayException(503, "UPSTREAM_TIMEOUT", "retry")))
        assertFalse(requiresNewQrLogin(java.io.IOException("offline")))
    }

    @Test fun transientGatewayFailuresRetryOnlySafeReadMethods() {
        assertTrue(isIdempotentQqReadMethod("CgiGetDiss"))
        assertTrue(isIdempotentQqReadMethod("GetPlaylistByUin"))
        assertTrue(isIdempotentQqReadMethod("UrlGetVkey"))
        assertTrue(isIdempotentQqReadMethod("DoSearchForQQMusicDesktop"))
        assertFalse(isIdempotentQqReadMethod("AddSonglist"))
        assertFalse(isIdempotentQqReadMethod("DelSonglist"))
        assertFalse(isIdempotentQqReadMethod("AddPlaylist"))

        assertTrue(isRecoverableGatewayReadFailure(QMusicGatewayResponseException("invalid json")))
        assertTrue(isRecoverableGatewayReadFailure(QMusicGatewayException(503, "UPSTREAM_TIMEOUT", "retry")))
        assertTrue(isRecoverableGatewayReadFailure(QMusicGatewayException(429, "RATE_LIMITED", "retry", 1_500L)))
        assertTrue(isRecoverableGatewayReadFailure(IOException("connection reset")))
        assertFalse(isRecoverableGatewayReadFailure(QMusicGatewayException(409, "RELOGIN_REQUIRED", "relogin")))
        assertFalse(isRecoverableGatewayReadFailure(IllegalStateException("business failure")))

        assertEquals(1_500L, gatewayReadRetryDelayMs(QMusicGatewayException(429, "RATE_LIMITED", "retry", 1_500L)))
        assertEquals(500L, gatewayReadRetryDelayMs(QMusicGatewayException(429, "RATE_LIMITED", "retry")))
        assertEquals(250L, gatewayReadRetryDelayMs(QMusicGatewayResponseException("invalid json")))
    }

    @Test fun invalidatedStreamRequestsCannotReturnTheirOldResult() {
        assertTrue(isCurrentStreamGeneration(captured = 4L, current = 4L))
        assertFalse(isCurrentStreamGeneration(captured = 4L, current = 5L))
        assertFalse(isCurrentStreamGeneration(captured = 4L, current = null))
    }
}
