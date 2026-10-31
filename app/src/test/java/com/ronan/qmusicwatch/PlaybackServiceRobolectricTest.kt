package com.ronan.qmusicwatch

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.ronan.qmusicwatch.playback.BACKGROUND_RECOVERY_ATTEMPTS
import com.ronan.qmusicwatch.playback.BACKGROUND_RECOVERY_DELAY_MS
import com.ronan.qmusicwatch.playback.BACKGROUND_SNAPSHOT_INTERVAL_MS
import com.ronan.qmusicwatch.playback.BACKGROUND_PLAYBACK_WAKE_MODE
import com.ronan.qmusicwatch.playback.BACKGROUND_AUTO_SKIP_ATTEMPTS
import com.ronan.qmusicwatch.playback.BACKGROUND_AUTO_SKIP_RETRY_DELAY_MS
import com.ronan.qmusicwatch.playback.BACKGROUND_TRANSITION_WAKE_LOCK_MS
import com.ronan.qmusicwatch.playback.PlaybackRecoveryRequest
import com.ronan.qmusicwatch.playback.PlaybackRecoveryTracker
import com.ronan.qmusicwatch.playback.PlaybackService
import com.ronan.qmusicwatch.playback.mergePlaybackProgressSnapshot
import com.ronan.qmusicwatch.playback.mergeRecoveredPlaybackSnapshot
import com.ronan.qmusicwatch.playback.prepareRecoveryReplacement
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
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackServiceRobolectricTest {
    @Test fun backgroundPlaybackKeepsCpuAndNetworkAwake() {
        assertEquals(C.WAKE_MODE_NETWORK, BACKGROUND_PLAYBACK_WAKE_MODE)
        assertTrue(BACKGROUND_SNAPSHOT_INTERVAL_MS in 5_000L..30_000L)
        assertTrue(BACKGROUND_RECOVERY_DELAY_MS in 500L..5_000L)
        assertTrue(BACKGROUND_RECOVERY_ATTEMPTS >= 3)
        assertTrue(BACKGROUND_AUTO_SKIP_ATTEMPTS >= 2)
        assertTrue(BACKGROUND_AUTO_SKIP_RETRY_DELAY_MS in 250L..5_000L)
        assertTrue(BACKGROUND_TRANSITION_WAKE_LOCK_MS in 10_000L..120_000L)
    }

    @Test fun consecutiveFailuresCannotBeClearedByTheOlderRecovery() {
        val tracker = PlaybackRecoveryTracker()
        val first = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/old",
                positionMs = 1_000,
            ),
        )
        val second = tracker.replace(
            PlaybackRecoveryRequest(
                mediaId = "track-a",
                failedUri = "https://heyboxlite.xyz/media/new",
                positionMs = 2_000,
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

    @Test fun successfulUrlRefreshesDoNotResetTheConsecutivePlaybackFailureBudget() {
        val tracker = PlaybackRecoveryTracker()
        repeat(BACKGROUND_RECOVERY_ATTEMPTS) { attempt ->
            val request = tracker.replace(recoveryRequest(uri = "https://dl.stream.qqmusic.qq.com/failed-$attempt"))
            assertTrue(tracker.tryStartAttempt(request))
            val replacement = "https://dl.stream.qqmusic.qq.com/replacement-$attempt"
            assertTrue(tracker.expectReplacement(request, replacement))
            assertNull(tracker.invalidateUnlessMatches(request.mediaId, replacement, applyingGeneration = null))
            // The refresh API and snapshot commit succeeded, but the replacement
            // immediately fails at the CDN without ever reaching STATE_READY.
            assertTrue(tracker.clearIfCurrent(request))
        }

        val nextFailure = tracker.replace(recoveryRequest(uri = "https://dl.stream.qqmusic.qq.com/final"))
        assertFalse(tracker.tryStartAttempt(nextFailure))
    }

    @Test fun onlyReadyForTheCurrentTrackResetsItsRecoveryBudget() {
        val tracker = PlaybackRecoveryTracker()
        val request = tracker.replace(recoveryRequest())
        repeat(BACKGROUND_RECOVERY_ATTEMPTS) { assertTrue(tracker.tryStartAttempt(request)) }
        assertFalse(tracker.tryStartAttempt(request))

        tracker.markPlaybackReady("track-b")
        assertFalse(tracker.tryStartAttempt(request))
        tracker.markPlaybackReady(request.mediaId)
        assertTrue(tracker.tryStartAttempt(request))
    }

    @Test fun changingTracksStartsANewBudgetEvenWhenThePreviousRequestWasCleared() {
        val tracker = PlaybackRecoveryTracker()
        val request = tracker.replace(recoveryRequest())
        repeat(BACKGROUND_RECOVERY_ATTEMPTS) { assertTrue(tracker.tryStartAttempt(request)) }
        assertTrue(tracker.clearIfCurrent(request))

        assertNull(tracker.invalidateUnlessMatches("track-b", "https://dl.stream.qqmusic.qq.com/b", null))
        val replayed = tracker.replace(recoveryRequest())
        assertTrue(tracker.tryStartAttempt(replayed))
    }

    @Test fun supersededRecoveriesDoNotConsumeTheCurrentAttempts() {
        val tracker = PlaybackRecoveryTracker()
        val old = tracker.replace(recoveryRequest())
        val current = tracker.replace(recoveryRequest(uri = "https://dl.stream.qqmusic.qq.com/new"))
        assertFalse(tracker.tryStartAttempt(old))
        repeat(BACKGROUND_RECOVERY_ATTEMPTS) { assertTrue(tracker.tryStartAttempt(current)) }
        assertFalse(tracker.tryStartAttempt(current))
    }

    @Test fun aUserOrNoisyPauseDuringRecoveryIsNotOverriddenByTheReplacement() {
        assertRecoveryReplacementIntent(playWhenReady = false)
    }

    @Test fun aRecoveryWithoutALaterPauseKeepsItsPlayingIntent() {
        assertRecoveryReplacementIntent(playWhenReady = true)
    }

    private fun recoveryRequest(uri: String = "https://dl.stream.qqmusic.qq.com/failed") =
        PlaybackRecoveryRequest(mediaId = "track-a", failedUri = uri, positionMs = 7_000)

    private fun assertRecoveryReplacementIntent(playWhenReady: Boolean) {
        var liveIntent = playWhenReady
        var replacementPosition = -1L
        val calls = mutableListOf<String>()
        val item = MediaItem.Builder().setMediaId("track-a")
            .setUri("https://dl.stream.qqmusic.qq.com/replacement").build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "setMediaItem" -> {
                    assertEquals(item, args!![0])
                    replacementPosition = args[1] as Long
                    null
                }
                "prepare" -> null
                "play" -> { liveIntent = true; null }
                "pause" -> { liveIntent = false; null }
                "setPlayWhenReady" -> { liveIntent = args!![0] as Boolean; null }
                else -> error("Unexpected player action: ${method.name}")
            }
        } as Player

        prepareRecoveryReplacement(player, item, positionMs = 7_000)

        assertEquals(playWhenReady, liveIntent)
        assertEquals(7_000L, replacementPosition)
        assertEquals(listOf("setMediaItem", "prepare"), calls)
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
