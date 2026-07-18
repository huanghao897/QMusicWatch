package com.ronan.qmusicwatch.model

import kotlinx.serialization.Serializable

@Serializable data class ApiEnvelope<T>(val ok: Boolean, val data: T? = null, val error: ApiError? = null)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class Track(
    val id: String, val title: String, val artists: List<String> = emptyList(), val album: String = "",
    val artworkUrl: String = "", val playable: Boolean = true, val qualities: List<String> = listOf("128"),
    val playedAt: Long? = null,
    val numericId: Long = 0, val mediaMid: String = "", val songType: Int = 0,
    val requiresVip: Boolean = false,
)
@Serializable data class MusicCollection(
    val id: String, val title: String, val artworkUrl: String = "", val trackCount: Int = -1,
    val directoryId: String = id, val owned: Boolean? = null,
)
@Serializable data class HomeData(val daily: List<Track>, val recommended: List<MusicCollection>)
@Serializable data class PagedTracks(val items: List<Track>, val nextCursor: String? = null)
@Serializable data class PagedCollections(val items: List<MusicCollection>, val nextCursor: String? = null)
@Serializable data class LyricsData(val original: String, val translation: String? = null, val wordSync: String? = null)
@Serializable data class StreamData(val url: String, val quality: String, val expiresAt: Long)
/**
 * A quality option is kept separate from [Track.qualities]: the latter is a
 * property of the file, while this model also describes the account's
 * entitlement and the reason an option is unavailable.
 */
@Serializable data class QualityEntitlement(
    val id: String,
    val label: String,
    val bitrateKbps: Int,
    val available: Boolean,
    val requiresVip: Boolean = false,
    val reason: String = "",
)
data class QualityResolution(
    val requested: String,
    val resolved: String,
    val requestedAvailable: Boolean,
    val reason: String = "",
)
@Serializable data class LibraryData(val liked: List<Track>, val playlists: List<MusicCollection>)
@Serializable data class SessionTokens(
    val accessToken: String = "", val refreshToken: String = "", val accountId: String,
    val provider: String = "qq", val upstreamCookie: String? = null,
)
@Serializable data class CookieExchange(val provider: String, val cookie: String)
@Serializable data class Ack(val accepted: Boolean)
@Serializable data class CollectionDetail(val title: String, val tracks: List<Track>)
@Serializable data class PlaybackSnapshot(
    val track: Track? = null, val queue: List<Track> = emptyList(), val positionMs: Long = 0,
    val queueReversed: Boolean = false,
    val streamUrl: String = "", val streamExpiresAt: Long = 0, val quality: String = "128",
    val ownerAccountId: String = "",
)
fun PlaybackSnapshot.belongsToAccount(accountId: String?): Boolean = accountId != null && ownerAccountId == accountId
@Serializable data class UserProfile(
    val displayName: String = "", val avatarUrl: String = "", val isVip: Boolean? = null,
    val vipExpireAt: Long? = null, val vipName: String = "",
    /** Stable, UI-friendly membership category (for example `svip` or `green_diamond`). */
    val vipType: String = "",
    /** Upstream membership tier, normalized to 0..3. */
    val vipLevel: Int = 0,
    /** Account-level quality rights. Old cached profiles decode with an empty list. */
    val qualityEntitlements: List<QualityEntitlement> = emptyList(),
)

internal const val QUALITY_STANDARD = "128"
internal const val QUALITY_HIGH = "320"

/** Normalizes seconds, milliseconds and the occasional microsecond timestamp. */
internal fun normalizeEpochSeconds(value: Long?): Long? = value?.let {
    when {
        it <= 0L -> null
        it >= 100_000_000_000_000_000L -> it / 1_000_000_000L
        it >= 100_000_000_000_000L -> it / 1_000_000L
        it >= 100_000_000_000L -> it / 1_000L
        else -> it
    }
}

internal fun UserProfile.isVipActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
    val expiry = normalizeEpochSeconds(vipExpireAt)
    if (expiry != null && expiry * 1_000L <= nowMillis) return false
    return when {
        isVip == true -> true
        isVip == null && expiry != null -> true
        else -> false
    }
}

internal fun normalizeQualityId(value: String?): String = when (value?.trim()?.lowercase()) {
    "320", "320k", "hq", "high", "high_quality" -> QUALITY_HIGH
    else -> QUALITY_STANDARD
}

private fun accountQualityOptions(profile: UserProfile?, nowMillis: Long): List<QualityEntitlement> {
    val active = profile?.isVipActive(nowMillis) == true
    val unknown = profile == null || profile.isVip == null
    val stored = profile?.qualityEntitlements.orEmpty().associateBy { normalizeQualityId(it.id) }
    val highReason = when {
        active -> ""
        unknown -> "会员权益尚未确认"
        else -> "需要有效会员权益"
    }
    return listOf(
        QualityEntitlement(QUALITY_STANDARD, "标准 128k", 128, true),
        QualityEntitlement(
            QUALITY_HIGH, "高品 320k", 320,
            available = active || (profile?.isVip != false && stored[QUALITY_HIGH]?.available == true && profile?.vipExpireAt == null),
            requiresVip = true,
            reason = if (active || (profile?.isVip != false && stored[QUALITY_HIGH]?.available == true && profile?.vipExpireAt == null)) "" else highReason,
        ),
    )
}

/** Returns the account and track intersection used by the quality picker. */
internal fun qualityAvailability(
    track: Track?,
    profile: UserProfile?,
    nowMillis: Long = System.currentTimeMillis(),
): List<QualityEntitlement> {
    val account = accountQualityOptions(profile, nowMillis).associateBy { it.id }
    val supported = track?.qualities.orEmpty().map(::normalizeQualityId).toSet().ifEmpty { setOf(QUALITY_STANDARD) }
    val vipRequired = track?.requiresVip == true
    return listOf(QUALITY_STANDARD, QUALITY_HIGH).map { id ->
        val base = account.getValue(id)
        val available = id in supported && (!vipRequired || profile?.isVipActive(nowMillis) == true) && base.available
        val reason = when {
            available -> ""
            id !in supported -> "当前歌曲不提供 ${base.label}"
            vipRequired && profile?.isVipActive(nowMillis) != true -> if (profile == null || profile.isVip == null) "会员权益尚未确认" else "这首歌需要会员权益"
            base.reason.isNotBlank() -> base.reason
            else -> "当前账号不可用"
        }
        base.copy(available = available, reason = reason)
    }
}

internal fun profileQualityOptions(profile: UserProfile?, nowMillis: Long = System.currentTimeMillis()): List<QualityEntitlement> =
    qualityAvailability(null, profile, nowMillis)

internal fun resolveQuality(
    preferred: String?,
    track: Track,
    profile: UserProfile?,
    nowMillis: Long = System.currentTimeMillis(),
): QualityResolution {
    val requested = normalizeQualityId(preferred)
    val options = qualityAvailability(track, profile, nowMillis)
    val requestedOption = options.first { it.id == requested }
    if (requestedOption.available) return QualityResolution(requested, requested, true)
    val fallback = options.firstOrNull(QualityEntitlement::available)
        ?: options.firstOrNull { it.id == QUALITY_STANDARD }
        ?: options.first()
    return QualityResolution(requested, fallback.id, false, requestedOption.reason)
}

internal fun normalizeUserProfile(profile: UserProfile): UserProfile {
    val expiry = normalizeEpochSeconds(profile.vipExpireAt)
    return if (expiry == profile.vipExpireAt) profile else profile.copy(vipExpireAt = expiry)
}

@Serializable data class CachedUserProfile(val accountId: String, val profile: UserProfile, val updatedAt: Long)
internal fun normalizeCachedUserProfile(value: CachedUserProfile): CachedUserProfile =
    value.copy(profile = normalizeUserProfile(value.profile))
@Serializable data class CachedAccountSnapshot(
    val accountId: String, val home: HomeData? = null, val library: LibraryData? = null, val updatedAt: Long,
)
@Serializable data class CachedAccountSnapshots(val items: List<CachedAccountSnapshot> = emptyList())
@Serializable data class ReleaseInfo(
    val tag: String, val title: String, val notes: String, val pageUrl: String,
    val apkUrl: String = "", val sha256: String = "", val newer: Boolean = false,
)
