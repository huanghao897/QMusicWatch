package com.ronan.qmusicwatch

import androidx.media3.common.C
import androidx.media3.session.MediaSession
import com.ronan.qmusicwatch.playback.BACKGROUND_RECOVERY_ATTEMPTS
import com.ronan.qmusicwatch.playback.BACKGROUND_RECOVERY_DELAY_MS
import com.ronan.qmusicwatch.playback.BACKGROUND_SNAPSHOT_INTERVAL_MS
import com.ronan.qmusicwatch.playback.BACKGROUND_PLAYBACK_WAKE_MODE
import com.ronan.qmusicwatch.playback.PlaybackRecoveryRequest
import com.ronan.qmusicwatch.playback.PlaybackRecoveryTracker
import com.ronan.qmusicwatch.playback.PlaybackService
import com.ronan.qmusicwatch.playback.mergePlaybackProgressSnapshot
import com.ronan.qmusicwatch.playback.mergeRecoveredPlaybackSnapshot
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import com.ronan.qmusicwatch.model.StreamData
import com.ronan.qmusicwatch.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackServiceRobolectricTest {
    @Test fun backgroundPlaybackKeepsCpuAndNetworkAwake() {
        assertEquals(C.WAKE_MODE_NETWORK, BACKGROUND_PLAYBACK_WAKE_MODE)
        assertTrue(BACKGROUND_SNAPSHOT_INTERVAL_MS in 5_000L..30_000L)
        assertTrue(BACKGROUND_RECOVERY_DELAY_MS in 500L..5_000L)
        assertTrue(BACKGROUND_RECOVERY_ATTEMPTS >= 3)
    }

    @Test fun consecutiveFailuresCannotBeClearedByTheOlderRecovery() {
        val tracker = PlaybackRecoveryTracker()
        val first = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/old",
                positionMs = 1_000,
                playWhenReady = true,
            ),
        )
        val second = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/new",
                positionMs = 2_000,
                playWhenReady = true,
            ),
        )

        assertFalse(tracker.clearIfCurrent(first))
        assertEquals(second, tracker.current())
        assertTrue(tracker.clearIfCurrent(second))
        assertNull(tracker.current())
    }

    @Test fun recoveredTransitionStaysValidWhenMedia3DeliversItAsynchronously() {
        val tracker = PlaybackRecoveryTracker()
        val request = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/expired",
                positionMs = 1_000,
                playWhenReady = true,
            ),
        )
        val refreshed = "https://heyboxlite.xyz/media/refreshed"

        assertTrue(tracker.expectReplacement(request, refreshed))
        assertNull(
            tracker.invalidateUnlessMatches(
                mediaId = request.mediaId,
                uri = refreshed,
                applyingGeneration = null,
            ),
        )
        assertTrue(tracker.isCurrent(request))
    }

    @Test fun switchingTracksInvalidatesAnInFlightRecoveryAndRejectsItsSnapshot() {
        val tracker = PlaybackRecoveryTracker()
        val request = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/a",
                positionMs = 4_000,
                playWhenReady = true,
            ),
        )
        val invalidated = tracker.invalidateUnlessMatches(
            mediaId = "track-b",
            uri = "https://heyboxlite.xyz/media/b",
            applyingGeneration = null,
        )
        val latest = PlaybackSnapshot(
            track = Track("track-b", "B"),
            queue = listOf(Track("track-b", "B")),
            ownerAccountId = "owner",
        )

        assertEquals(request, invalidated)
        assertFalse(tracker.isCurrent(request))
        assertNull(
            mergeRecoveredPlaybackSnapshot(
                latest,
                "owner",
                request,
                StreamData("https://heyboxlite.xyz/media/refreshed-a", "hq", 99_000),
            ),
        )
    }

    @Test fun recoveryAndProgressMergesPreserveTheLatestQueueState() {
        val current = Track("track-a", "A")
        val latestQueue = listOf(current, Track("track-c", "C"), Track("track-b", "B"))
        val snapshot = PlaybackSnapshot(
            track = current,
            queue = latestQueue,
            positionMs = 100,
            queueReversed = true,
            streamUrl = "https://heyboxlite.xyz/media/expired",
            streamExpiresAt = 1,
            quality = "standard",
            ownerAccountId = "owner",
        )
        val request = PlaybackRecoveryRequest(
            generation = 7,
            mediaId = current.id,
            failedUri = snapshot.streamUrl,
            positionMs = 7_000,
            playWhenReady = true,
        )

        val recovered = mergeRecoveredPlaybackSnapshot(
            snapshot,
            "owner",
            request,
            StreamData("https://heyboxlite.xyz/media/refreshed", "hq", 99_000),
        )!!
        val progressed = mergePlaybackProgressSnapshot(
            recovered,
            "owner",
            current.id,
            recovered.streamUrl,
            8_000,
        )!!

        assertEquals(latestQueue, recovered.queue)
        assertTrue(recovered.queueReversed)
        assertEquals(7_000, recovered.positionMs)
        assertEquals("hq", recovered.quality)
        assertEquals(latestQueue, progressed.queue)
        assertEquals(8_000, progressed.positionMs)
    }

    @Test fun aNewRemoteUriIsNotPersistedWithThePreviousUrisExpiry() {
        val track = Track("track-a", "A")
        val expired = "https://isure.stream.qqmusic.qq.com/M500expired.mp3?vkey=old"
        val refreshed = "https://isure.stream.qqmusic.qq.com/M500refreshed.mp3?vkey=new"
        val snapshot = PlaybackSnapshot(
            track = track,
            queue = listOf(track),
            streamUrl = expired,
            streamExpiresAt = 50_000,
            ownerAccountId = "owner",
        )

        val changed = mergePlaybackProgressSnapshot(
            snapshot = snapshot,
            accountId = "owner",
            mediaId = track.id,
            uri = refreshed,
            positionMs = 2_000,
        )!!

        assertEquals(refreshed, changed.streamUrl)
        assertEquals(0L, changed.streamExpiresAt)
    }

    @Test fun mediaSessionPublishesPreviousAndNextNotificationButtons() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        try {
            val field = PlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }
            val session = field.get(service) as MediaSession
            val actions = session.mediaButtonPreferences.map { it.sessionCommand?.customAction }
            assertEquals(2, actions.size)
            assertTrue(actions.any { it?.endsWith(".PREVIOUS") == true })
            assertTrue(actions.any { it?.endsWith(".NEXT") == true })
        } finally {
            controller.destroy()
        }
    }
}
