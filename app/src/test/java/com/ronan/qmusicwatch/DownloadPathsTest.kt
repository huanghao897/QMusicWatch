package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.download.cachedArtworkFile
import com.ronan.qmusicwatch.download.cachedLyricsFile
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPathsTest {
    @Test fun offlineMetadataUsesTheAudioOwnerPath() {
        val audio = "C:/private/offline/account/song.audio"
        assertEquals("C:\\private\\offline\\account\\song.lyrics.json", cachedLyricsFile(audio).path)
        assertEquals("C:\\private\\offline\\account\\song.cover", cachedArtworkFile(audio).path)
    }
}
