package com.ronan.qmusicwatch.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

enum class PlaybackFailureType { SESSION_EXPIRED, ENTITLEMENT, REGION, ADDRESS_EXPIRED, NETWORK, API_CHANGED, UNKNOWN }

data class PlaybackFailure(
    val type: PlaybackFailureType,
    val message: String,
    val retryable: Boolean = false,
)

data class PlaybackErrorEvent(
    val error: PlaybackException,
    val mediaId: String,
    val positionMs: Long,
    val isLocalFile: Boolean,
)

internal const val PLAYBACK_RECOVERY_EXHAUSTED_MESSAGE = "自动恢复失败，请重新播放"

internal fun playbackHttpStatus(error: Throwable): Int? =
    generateSequence(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode

internal fun requiresImmediateStreamRefreshStatus(status: Int?): Boolean =
    status in setOf(401, 403, 404, 410)

internal fun requiresImmediateStreamRefresh(error: Throwable): Boolean =
    requiresImmediateStreamRefreshStatus(playbackHttpStatus(error))

internal fun classifyPlaybackFailure(error: Throwable): PlaybackFailure {
    val chain = generateSequence(error) { it.cause }.toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    val status = playbackHttpStatus(error)
    return when {
        text.contains("自动恢复失败") ->
            PlaybackFailure(PlaybackFailureType.UNKNOWN, PLAYBACK_RECOVERY_EXHAUSTED_MESSAGE)
        status in setOf(401, 403, 404, 410) -> PlaybackFailure(PlaybackFailureType.ADDRESS_EXPIRED, "播放地址已失效", true)
        text.contains("登录凭据已失效") || text.contains("请重新登录") -> PlaybackFailure(PlaybackFailureType.SESSION_EXPIRED, "登录状态已失效，请重新登录")
        text.contains("歌曲信息已失效") || text.contains("歌曲标识无效") ->
            PlaybackFailure(PlaybackFailureType.API_CHANGED, "歌曲信息已失效，请重新搜索后播放")
        text.contains("未提供播放地址") -> PlaybackFailure(PlaybackFailureType.UNKNOWN, "QQ 音乐未提供播放地址，可能受版权、地区或账号权益限制")
        text.contains("vip") || text.contains("会员") || text.contains("购买") || text.contains("权益") ->
            PlaybackFailure(PlaybackFailureType.ENTITLEMENT, "当前账号没有这首歌的播放权益")
        text.contains("地区") || text.contains("版权") || text.contains("region") || text.contains("copyright") ->
            PlaybackFailure(PlaybackFailureType.REGION, "歌曲受版权或地区限制")
        text.contains("响应格式") || text.contains("字段") || text.contains("parse") || text.contains("json") ->
            PlaybackFailure(PlaybackFailureType.API_CHANGED, "QQ 音乐接口返回格式发生变化")
        chain.any { it is IOException } || text.contains("timeout") || text.contains("network") || text.contains("connection") || text.contains("cdn") ->
            PlaybackFailure(PlaybackFailureType.NETWORK, "网络或播放 CDN 连接中断", true)
        else -> PlaybackFailure(PlaybackFailureType.UNKNOWN, "播放失败，请导出日志反馈")
    }
}
