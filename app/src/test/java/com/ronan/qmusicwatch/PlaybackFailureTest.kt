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

    @Test fun invalidSearchTrackHasAnActionableMessage() {
        val failure = classifyPlaybackFailure(IllegalStateException("歌曲信息已失效，请重新搜索后播放"))
        assertEquals(PlaybackFailureType.API_CHANGED, failure.type)
        assertEquals("歌曲信息已失效，请重新搜索后播放", failure.message)
        assertEquals(false, failure.retryable)
    }

    @Test fun genericMissingStreamIsNotMisreportedAsVipOnly() {
        val failure = classifyPlaybackFailure(IllegalStateException("QQ 音乐未提供播放地址，可能存在版权、地区或账号权益限制"))
        assertEquals(PlaybackFailureType.UNKNOWN, failure.type)
        assertEquals("QQ 音乐未提供播放地址，可能受版权、地区或账号权益限制", failure.message)
    }

    @Test fun expiredSessionIsNotMisreportedAsEntitlement() {
        val failure = classifyPlaybackFailure(IllegalStateException("登录凭据已失效，请重新登录"))
        assertEquals(PlaybackFailureType.SESSION_EXPIRED, failure.type)
        assertEquals("登录状态已失效，请重新登录", failure.message)
    }
}
