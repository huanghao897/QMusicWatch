package com.ronan.qmusicwatch.model

import kotlinx.serialization.Serializable

@Serializable data class ApiEnvelope<T>(val ok: Boolean, val data: T? = null, val error: ApiError? = null)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class Track(
    val id: String, val title: String, val artists: List<String> = emptyList(), val album: String = "",
    val artworkUrl: String = "", val playable: Boolean = true, val qualities: List<String> = listOf("standard"),
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
    val streamUrl: String = "", val streamExpiresAt: Long = 0, val quality: String = "standard",
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

internal const val QUALITY_STANDARD = "standard"
internal const val QUALITY_HQ = "hq"
internal const val QUALITY_SQ = "sq"
internal const val QUALITY_HI_RES = "hires"
internal const val QUALITY_LEGACY_UNKNOWN = "legacy_unknown"

internal data class AudioQualitySpec(
    val id: String,
    val label: String,
    val shortLabel: String,
    val format: String,
    val bitrateKbps: Int,
    val requiresVip: Boolean,
    val clientSupported: Boolean,
    val rank: Int,
)

private val audioQualitySpecs = listOf(
    AudioQualitySpec(QUALITY_STANDARD, "标准音质", "标准", "MP3 · 128 kbps", 128, false, true, 0),
    AudioQualitySpec(QUALITY_HQ, "HQ · 高品质", "HQ", "MP3 · 320 kbps", 320, true, true, 1),
    AudioQualitySpec(QUALITY_SQ, "SQ · 无损品质", "SQ", "FLAC · 无损", 0, true, true, 2),
    AudioQualitySpec(QUALITY_HI_RES, "Hi-Res · 高解析无损", "Hi-Res", "24-bit 无损", 0, true, false, 3),
)

internal fun allAudioQualitySpecs(): List<AudioQualitySpec> = audioQualitySpecs
internal fun audioQualitySpec(value: String?): AudioQualitySpec =
    audioQualitySpecs.first { it.id == normalizeQualityId(value) }
internal fun qualityLabel(value: String?): String =
    if (value == QUALITY_LEGACY_UNKNOWN) "旧缓存（音质未知）" else audioQualitySpec(value).label
internal fun qualityShortLabel(value: String?): String =
    if (value == QUALITY_LEGACY_UNKNOWN) "旧缓存" else audioQualitySpec(value).shortLabel
internal fun qualityRank(value: String?): Int = audioQualitySpec(value).rank
internal fun reportedQualityId(value: String?): String =
    if (value == QUALITY_LEGACY_UNKNOWN) QUALITY_LEGACY_UNKNOWN else normalizeQualityId(value)
internal fun qualityFallbackOrder(value: String?): List<String> {
    val requestedRank = qualityRank(value)
    return audioQualitySpecs.asReversed().filter { it.clientSupported && it.rank <= requestedRank }.map(AudioQualitySpec::id)
}

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
    "320", "320k", "hq", "high", "high_quality" -> QUALITY_HQ
    "flac", "lossless", "sq", "lossless_quality" -> QUALITY_SQ
    "hires", "hi-res", "flac24bit", "24bit", "hr" -> QUALITY_HI_RES
    else -> QUALITY_STANDARD
}

private fun accountQualityOptions(profile: UserProfile?, nowMillis: Long): List<QualityEntitlement> {
    val active = profile?.isVipActive(nowMillis) == true && !profile?.vipName.orEmpty().contains("听书")
    val unknown = profile == null || profile.isVip == null
    return audioQualitySpecs.map { spec ->
        val available = spec.clientSupported
        QualityEntitlement(
            id = spec.id,
            label = spec.label,
            bitrateKbps = spec.bitrateKbps,
            available = available,
            requiresVip = spec.requiresVip,
            reason = when {
                !spec.clientSupported -> "当前版本暂未完成兼容验证"
                !spec.requiresVip -> ""
                active -> "当前音乐会员权益已确认"
                unknown -> "播放时由 QQ 音乐验证会员权益"
                else -> "可能需要豪华绿钻或超级会员，播放时由 QQ 音乐验证"
            },
        )
    }
}

/** Returns the account and track intersection used by the quality picker. */
internal fun qualityAvailability(
    track: Track?,
    profile: UserProfile?,
    nowMillis: Long = System.currentTimeMillis(),
): List<QualityEntitlement> {
    val account = accountQualityOptions(profile, nowMillis).associateBy { it.id }
    val supported = if (track == null) {
        audioQualitySpecs.mapTo(mutableSetOf(), AudioQualitySpec::id)
    } else {
        track.qualities.map(::normalizeQualityId).toSet().ifEmpty { setOf(QUALITY_STANDARD) }
    }
    val vipRequired = track?.requiresVip == true
    return audioQualitySpecs.map { spec ->
        val id = spec.id
        val base = account.getValue(id)
        val available = id in supported && base.available
        val reason = when {
            id !in supported -> "当前歌曲不提供 ${base.label}"
            !base.available -> base.reason.ifBlank { "当前版本不可用" }
            vipRequired && profile?.isVipActive(nowMillis) != true -> "这首歌将在播放时由 QQ 音乐验证会员权益"
            base.reason.isNotBlank() -> base.reason
            else -> ""
        }
        base.copy(available = available, requiresVip = base.requiresVip || vipRequired, reason = reason)
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
    val requestedRank = qualityRank(requested)
    val fallback = options.filter(QualityEntitlement::available)
        .filter { qualityRank(it.id) <= requestedRank }
        .maxByOrNull { qualityRank(it.id) }
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
