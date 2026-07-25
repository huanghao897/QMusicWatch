package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.qmusicAlbumArtworkUrl
import com.ronan.qmusicwatch.network.qmusicAvatarUrl
import com.ronan.qmusicwatch.network.safeLocalOrGatewayUri
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class QMusicGatewayTest {
    @Test fun stableMediaUrlsUseTheNewServer() {
        assertEquals(
            "https://203.160.55.168/api/qmusic-watch/gateway/artwork/album/albumMID123.jpg",
            qmusicAlbumArtworkUrl("albumMID123"),
        )
        assertEquals(
            "https://203.160.55.168/api/qmusic-watch/gateway/avatar/qq/12345.jpg",
            qmusicAvatarUrl("o12345"),
        )
        assertEquals("", qmusicAlbumArtworkUrl("../unsafe"))
        assertEquals("", qmusicAvatarUrl("not-a-uin"))
    }

    @Test fun onlyIssuedGatewayMediaPathsAreTrusted() {
        val issued = "https://203.160.55.168/api/qmusic-watch/gateway/media/${"t".repeat(32)}/M500song.mp3"
        assertEquals(issued, trustedQMusicMediaUrl(issued))
        assertEquals("", trustedQMusicMediaUrl("https://8.138.134.236/api/qmusic-watch/gateway/media/${"t".repeat(32)}/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("https://203.160.55.168/api/qmusic-watch/gateway/media/short/song.mp3"))
        assertEquals("", trustedQMusicMediaUrl("$issued?redirect=https://example.com"))
        assertEquals("", trustedQMusicMediaUrl("https://isure.stream.qqmusic.qq.com/M500song.mp3"))
    }

    @Test fun localFilesRemainUsableButOtherRemoteHostsAreRejected() {
        assertEquals("file:///data/user/0/com.ronan.qmusicwatch/files/song", safeLocalOrGatewayUri("file:///data/user/0/com.ronan.qmusicwatch/files/song"))
        assertEquals("", safeLocalOrGatewayUri("https://example.com/song.mp3"))
    }
}
