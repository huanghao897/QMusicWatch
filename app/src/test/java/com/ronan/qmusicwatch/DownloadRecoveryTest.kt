package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.download.cacheDownloadLyrics
import com.ronan.qmusicwatch.download.cachedLyricsFile
import com.ronan.qmusicwatch.download.resetPartialAfterRangeFailure
import com.ronan.qmusicwatch.model.LyricsData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadRecoveryTest {
    @Test fun completedPartialRejectedByRangeRestartsOnlyOnce() {
        val directory = Files.createTempDirectory("qmusic-audio-range").toFile()
        try {
            val part = File(directory, "song.part").apply { writeText("complete-audio") }
            val start = part.length()

            assertTrue(resetPartialAfterRangeFailure(416, start, part, retriedFromStart = false))
            assertFalse(part.exists())
            // The worker's next request starts at zero, without a Range header.
            assertEquals(0L, part.length())
            part.writeText("new-audio")
            assertFalse(resetPartialAfterRangeFailure(416, part.length(), part, retriedFromStart = true))
            assertEquals("new-audio", part.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun fullResponsesAndFirstRequestRangeErrorsDoNotDiscardData() {
        val part = File.createTempFile("qmusic-audio-range", ".part")
        try {
            part.writeText("partial")
            assertFalse(resetPartialAfterRangeFailure(200, part.length(), part, false))
            assertFalse(resetPartialAfterRangeFailure(206, part.length(), part, false))
            assertFalse(resetPartialAfterRangeFailure(416, 0, part, false))
            assertEquals("partial", part.readText())
        } finally { part.delete() }
    }

    @Test fun optionalLyricsWriteFailureDoesNotFailCompletedAudio() = runBlocking {
        val directory = Files.createTempDirectory("qmusic-audio-lyrics").toFile()
        try {
            val audio = File(directory, "song.audio").apply { writeText("complete-audio") }
            // An existing directory makes writeText fail deterministically.
            assertTrue(cachedLyricsFile(audio.absolutePath).mkdir())

            cacheDownloadLyrics(audio.absolutePath) { LyricsData("[00:00.00]line") }

            assertEquals("complete-audio", audio.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun optionalLyricsFetchFailureDoesNotFailCompletedAudio() = runBlocking {
        val audio = File.createTempFile("qmusic-audio-lyrics", ".audio")
        try {
            audio.writeText("complete-audio")
            cacheDownloadLyrics(audio.absolutePath) { throw java.io.IOException("lyrics unavailable") }
            assertEquals("complete-audio", audio.readText())
        } finally { audio.delete() }
    }

    @Test fun optionalLyricsCancellationIsNotSwallowed() = runBlocking {
        val cancelled = CancellationException("download stopped")
        try {
            cacheDownloadLyrics("unused.audio") { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
