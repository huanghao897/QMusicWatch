package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.download.cachedArtworkFile
import com.ronan.qmusicwatch.download.cachedLyricsFile
import com.ronan.qmusicwatch.download.offlineAudioRelativePath
import com.ronan.qmusicwatch.download.hasDownloadSpace
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPathsTest {
    @Test fun offlineMetadataUsesTheAudioOwnerPath() {
        val audio = "C:/private/offline/account/song.audio"
        assertEquals("C:\\private\\offline\\account\\song.lyrics.json", cachedLyricsFile(audio).path)
        assertEquals("C:\\private\\offline\\account\\song.cover", cachedArtworkFile(audio).path)
    }

    @Test fun offlinePathIsStableAndDoesNotExposeAccountOrTrackIds() {
        val path = offlineAudioRelativePath("account-123", "track-456")
        assertEquals(true, path.startsWith("offline/"))
        assertEquals(false, path.contains("account-123"))
        assertEquals(false, path.contains("track-456"))
        assertEquals(true, path.endsWith(".audio"))
    }

    @Test fun storageReserveIncludesTheRemainingDownloadSize() {
        val mb = 1024L * 1024
        assertEquals(true, hasDownloadSpace(400 * mb, 100 * mb))
        assertEquals(false, hasDownloadSpace(300 * mb, 100 * mb))
        assertEquals(false, hasDownloadSpace(200 * mb, -1))
    }
}
