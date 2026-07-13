package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.playback.PlaybackFailureType
import com.ronan.qmusicwatch.playback.classifyPlaybackFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureTest {
    @Test fun networkErrorsCanRetry() {
        val failure = classifyPlaybackFailure(IOException("connection reset"))
        assertEquals(PlaybackFailureType.NETWORK, failure.type)
        assertTrue(failure.retryable)
    }

    @Test fun entitlementErrorsAreExplained() {
        val failure = classifyPlaybackFailure(IllegalStateException("这首歌需要 VIP 或购买"))
        assertEquals(PlaybackFailureType.ENTITLEMENT, failure.type)
        assertEquals(false, failure.retryable)
    }

    @Test fun responseShapeErrorsAreClassified() {
        val failure = classifyPlaybackFailure(IllegalStateException("QQ 音乐响应格式已变化"))
        assertEquals(PlaybackFailureType.API_CHANGED, failure.type)
    }
}
