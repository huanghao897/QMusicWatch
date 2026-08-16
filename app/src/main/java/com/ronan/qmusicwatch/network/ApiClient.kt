package com.ronan.qmusicwatch.network

import android.content.Context
import com.ronan.qmusicwatch.login.MusicCookie
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.lyrics.QqQrcDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun normalizeHttpsUrl(value: String): String =
    preferDirectQMusicArtworkUrl(normalizeQqHttpsUrl(value))

private val invalidQqSongMids = setOf("null", "undefined", "nil")

internal fun isUsableQqSongMid(value: String): Boolean {
    val normalized = value.trim()
    return normalized.isNotEmpty() &&
        normalized.any { it != '0' } &&
        normalized.lowercase() !in invalidQqSongMids
}

private fun firstUsableQqSongMid(vararg values: String): String =
    values.asSequence().map(String::trim).firstOrNull(::isUsableQqSongMid).orEmpty()

internal class QqBusinessException(val businessCode: Int, message: String) : IllegalStateException(message)
internal class QqCredentialExpiredException(message: String) : IllegalStateException(message)
internal class QqHttpException(
    val statusCode: Int,
    message: String,
    val retryAfterMs: Long = 0L,
) : IllegalStateException(message)
internal class QqResponseException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun isIdempotentQqReadMethod(method: String): Boolean =
    method.startsWith("Get") ||
        method.startsWith("CgiGet") ||
        method.startsWith("UrlGet") ||
        method.startsWith("DoSearch") ||
        method.startsWith("get_") ||
        method.startsWith("query", ignoreCase = true) ||
        method == "vip_login_base"

internal fun isRecoverableQqReadFailure(error: Throwable): Boolean = when (error) {
    is QqResponseException -> true
    is QqHttpException -> error.statusCode == 429 || error.statusCode in 500..599
    is IOException -> true
    else -> false
}

internal fun qqReadRetryDelayMs(error: Throwable): Long = when (error) {
    is QqHttpException -> when (error.statusCode) {
        429 -> error.retryAfterMs.coerceIn(500L, 2_000L)
        else -> 350L
    }
    is QqResponseException -> 250L
    else -> 300L
}

internal fun isCurrentStreamGeneration(captured: Long, current: Long?): Boolean =
    current == captured

internal fun shouldProbePlaybackCredential(
    qualityIndex: Int,
    hasFallback: Boolean,
    allowRecovery: Boolean,
): Boolean = qualityIndex == 0 && !hasFallback && allowRecovery

internal data class QqCredentialRefreshRequest(
    val comm: JsonObject,
    val param: JsonObject,
)

internal fun qqCredentialRefreshRequest(
    musicId: String,
    musicKey: String,
    openId: String,
    accessToken: String,
    refreshToken: String,
    refreshKey: String,
    unionId: String,
    expiredAt: Long,
    guid: String,
    wid: String,
    deviceName: String,
): QqCredentialRefreshRequest {
    val numericMusicId = musicId.toLongOrNull()?.takeIf { it > 0L }
        ?: throw IllegalArgumentException("账号标识无效")
    require(musicKey.isNotBlank() && openId.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()) {
        "登录续期凭据不完整"
    }
    require(guid.isNotBlank() && wid.matches(Regex("\\d{1,20}")) && deviceName.isNotBlank()) {
        "登录设备标识无效"
    }
    val expiry = expiredAt.coerceAtLeast(0L)
    return QqCredentialRefreshRequest(
        param = objOf(
            "access_token" to accessToken,
            "appid" to 100497308,
            "deviceName" to deviceName,
            "deviceType" to "Windows",
            "expired_in" to expiry,
            "forceRefreshToken" to 0,
            "musicid" to numericMusicId,
            "musickey" to musicKey,
            "onlyNeedAccessToken" to 0,
            "openid" to openId,
            "refresh_key" to refreshKey,
            "refresh_token" to refreshToken,
        ),
        comm = objOf(
            "_channelid" to "0",
            "_os_version" to "6.2.9200-2",
            "authst" to musicKey,
            "ct" to "19",
            "cv" to "2192",
            "guid" to guid,
            "patch" to "118",
            "psrf_access_token_expiresAt" to expiry,
            "psrf_qqaccess_token" to accessToken,
            "psrf_qqopenid" to openId,
            "psrf_qqunionid" to unionId,
            "tmeAppID" to "qqmusic",
            "tmeLoginType" to 2,
            "uin" to musicId,
            "wid" to wid,
        ),
    )
}

private fun objOf(vararg entries: Pair<String, Any?>): JsonObject =
    buildJsonObject { entries.forEach { (key, value) -> put(key, jsonValue(value)) } }

private fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

private const val LOGIN_USER_INFO_MODULE = "music.UserInfo.userInfoServer"
private const val LOGIN_CREDENTIAL_PROBE_METHOD = "GetLoginUserInfo"
private const val MAX_QQ_RESPONSE_BYTES = 4 * 1024 * 1024
private val passthroughCookieNames = listOf(
    "login_type", "tmeLoginMethod", "wxuin", "p_lskey", "euin",
    "wxopenid", "openid", "unionid", "access_token", "refresh_key",
    "refresh_token", "p_skey", "skey", "expired_at", "musickeyCreateTime",
    "keyExpiresIn",
)

internal fun shouldRefreshCredential(module: String, method: String, code: Int): Boolean =
    code in setOf(104400, 104401) ||
        (code == 1000 && module == LOGIN_USER_INFO_MODULE && method == LOGIN_CREDENTIAL_PROBE_METHOD)

private fun isLoginCredentialProbe(module: String, method: String): Boolean =
    module == LOGIN_USER_INFO_MODULE && method == LOGIN_CREDENTIAL_PROBE_METHOD

internal fun requiresNewQrLogin(error: Throwable): Boolean =
    error is QqCredentialExpiredException

private fun JsonObject.hasPositiveNumber(vararg names: String): Boolean = names.any { name ->
    (this[name] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true
}

internal fun parseQqQualityIds(item: JsonObject, file: JsonObject = item["file"] as? JsonObject ?: JsonObject(emptyMap())): List<String> =
    buildList {
        if (item.hasPositiveNumber("size128", "size_128mp3") || file.hasPositiveNumber("size128", "size_128mp3")) add(QUALITY_STANDARD)
        if (item.hasPositiveNumber("size320", "size_320mp3") || file.hasPositiveNumber("size320", "size_320mp3")) add(QUALITY_HQ)
        if (item.hasPositiveNumber("sizeflac", "size_flac") || file.hasPositiveNumber("sizeflac", "size_flac")) add(QUALITY_SQ)
        if (item.hasPositiveNumber("sizehires", "size_hires") || file.hasPositiveNumber("sizehires", "size_hires")) add(QUALITY_HI_RES)
    }.ifEmpty { listOf(QUALITY_STANDARD) }

internal fun qqStreamFileName(quality: String, mediaMid: String): String {
    require(mediaMid.isNotBlank()) { "歌曲缺少媒体标识" }
    val (prefix, extension) = when (normalizeQualityId(quality)) {
        QUALITY_SQ -> "F000" to "flac"
        QUALITY_HQ -> "M800" to "mp3"
        QUALITY_HI_RES -> throw IllegalArgumentException("Hi-Res 资源格式尚未完成兼容验证")
        else -> "M500" to "mp3"
    }
    return "$prefix$mediaMid.$extension"
}

internal data class QqStreamPath(val value: String, val sourceKey: String)

internal fun inferQqStreamQuality(path: String, sourceKey: String): String {
    val upper = path.uppercase()
    return when {
        "F000" in upper -> QUALITY_SQ
        "M800" in upper -> QUALITY_HQ
        "M500" in upper || "C400" in upper || sourceKey in setOf("opi128kurl", "opi96kurl") -> QUALITY_STANDARD
        else -> QUALITY_STANDARD
    }
}

internal fun higherQualityStream(current: StreamData?, candidate: StreamData): StreamData =
    if (current == null || qualityRank(candidate.quality) > qualityRank(current.quality)) candidate else current

private val systemLikedPlaylistTitles = setOf(
    "我喜欢", "我喜欢的音乐", "我喜欢的歌曲",
    "我喜歡", "我喜歡的音樂", "我喜歡的歌曲",
)

internal fun isSystemLikedPlaylist(value: MusicCollection): Boolean {
    val directoryId = value.directoryId.trim().toLongOrNull()
    if (directoryId == 201L) return true

    val normalizedTitle = value.title.filterNot(Char::isWhitespace)
    return value.owned == false && directoryId == 0L && normalizedTitle in systemLikedPlaylistTitles
}

private fun stableTrackArtwork(track: Track): Track {
    val current = preferDirectQMusicArtworkUrl(track.artworkUrl)
    val stable = current.ifBlank { qmusicSongArtworkUrl(track.id) }
    return if (stable == track.artworkUrl) track else track.copy(artworkUrl = stable)
}

internal fun normalizeLibraryData(value: LibraryData): LibraryData =
    value.copy(
        liked = value.liked.map(::stableTrackArtwork),
        playlists = deduplicatePlaylists(value.playlists.filterNot(::isSystemLikedPlaylist)),
    )

private fun playlistIdentityKey(value: MusicCollection): String? =
    value.directoryId.trim().takeIf(String::isNotBlank)?.let { "directory:$it" }
        ?: value.id.trim().takeIf(String::isNotBlank)?.let { "id:$it" }

private fun deduplicatePlaylists(values: List<MusicCollection>): List<MusicCollection> {
    val seen = mutableSetOf<String>()
    return values.filter { value ->
        playlistIdentityKey(value)?.let(seen::add) ?: true
    }
}

internal fun mergeLibraryPlaylists(
    accountPlaylists: List<MusicCollection>,
    favoritePlaylists: List<MusicCollection>,
): List<MusicCollection> {
    val favorites = deduplicatePlaylists(favoritePlaylists.filterNot(::isSystemLikedPlaylist))
    val favoriteKeys = favorites.mapNotNullTo(mutableSetOf(), ::playlistIdentityKey)
    val account = deduplicatePlaylists(
        accountPlaylists.filterNot(::isSystemLikedPlaylist).filter { playlist ->
            playlistIdentityKey(playlist) !in favoriteKeys
        },
    )
    return account + favorites
}

internal fun parseAccountPlaylists(root: JsonElement): List<MusicCollection> {
    fun objects(value: JsonElement, ownedHint: Boolean? = null): Sequence<Pair<JsonObject, Boolean?>> = sequence {
        when (value) {
            is JsonObject -> {
                yield(value to ownedHint)
                value.forEach { (key, child) ->
                    val hint = when {
                        key.contains("collect", true) || key.contains("favorite", true) -> false
                        key.contains("create", true) || key.contains("playlist", true) -> true
                        else -> ownedHint
                    }
                    yieldAll(objects(child, hint))
                }
            }
            is JsonArray -> value.forEach { yieldAll(objects(it, ownedHint)) }
            else -> Unit
        }
    }
    fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.intOrNull ?: 0
    return objects(root).mapNotNull { (item, ownedHint) ->
        val dirId = item.text("dirId").ifBlank { item.text("dirid") }
        val id = item.text("tid").ifBlank { item.text("id") }.ifBlank { dirId }
        val title = item.text("dirName").ifBlank { item.text("title") }.ifBlank { item.text("name") }
        val owned = item.text("isOwn").ifBlank { item.text("is_self") }.toIntOrNull()?.let { it > 0 } ?: ownedHint
        if (dirId.isBlank() || id.isBlank() || title.isBlank()) null else MusicCollection(
            id, title, normalizeHttpsUrl(item.text("picUrl").ifBlank { item.text("picurl") }),
            item.number("songNum").takeIf { it > 0 } ?: item.number("songnum"), dirId, owned,
        )
    }.distinctBy { it.directoryId }.filterNot(::isSystemLikedPlaylist).toList()
}

internal fun parseFavoritePlaylists(root: JsonElement): List<MusicCollection> {
    fun objects(value: JsonElement): Sequence<JsonObject> = sequence {
        when (value) {
            is JsonObject -> { yield(value); value.values.forEach { yieldAll(objects(it)) } }
            is JsonArray -> value.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }
    fun JsonObject.text(vararg names: String) = names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
    fun JsonObject.number(vararg names: String) = names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.intOrNull } ?: -1
    return objects(root).mapNotNull { item ->
        val id = item.text("tid", "dissid", "id", "dirId", "dirid")
        val title = item.text("dissname", "dirName", "title", "name")
        val hasPlaylistShape = item.keys.any { it in setOf("tid", "dissid", "songNum", "songnum", "song_count") }
        if (!hasPlaylistShape || id.isBlank() || title.isBlank()) null else MusicCollection(
            id, title, normalizeHttpsUrl(item.text("picUrl", "picurl", "logo", "imgurl", "pic")),
            item.number("songNum", "songnum", "song_count", "total"),
            directoryId = item.text("dirId", "dirid").ifBlank { id }, owned = false,
        )
    }.distinctBy(MusicCollection::directoryId).filterNot(::isSystemLikedPlaylist).toList()
}

private data class MembershipEvidence(
    val enabled: Boolean?,
    val expiry: Long?,
    val label: String,
    val type: String,
    val rank: Int,
)

private val membershipStatusKeys = arrayOf(
    "isVip", "is_vip", "isSVip", "is_svip", "isSvip", "vip", "svip",
    "vipFlag", "vip_flag", "svipFlag", "svip_flag", "svip_status", "vipStatus",
    "HugeVip", "LMFlag",
)
private val membershipTypeKeys = arrayOf(
    "vip_type", "vipType", "viptype", "svip_type", "svipType",
    "music_vip_level", "green_vip_level", "luxury_vip_level", "super_vip_level",
)
private val membershipExpiryKeys = arrayOf(
    "HugeVipEnd", "LMEnd", "vipEndTime", "vip_end_time", "vip_endtime", "svipEndTime", "svip_end_time",
    "vipExpireTime", "vip_expire_time", "vipExpireDate", "vip_expire_date",
    "expireTime", "expire_time", "expireDate", "expire_date", "endTime", "end_time", "endDate",
    "expiry", "expiration", "expire",
)

private fun membershipBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
    "1", "true", "yes", "on", "vip", "svip", "valid", "active" -> true
    "0", "false", "no", "off", "none", "invalid", "inactive" -> false
    else -> value.trim().toLongOrNull()?.let { it > 0 }
}

private fun membershipTypeFrom(rank: Int, path: String, label: String): String = when {
    rank >= 3 || path.contains("svip", true) || label.contains("超级") || label.contains("SVIP", true) -> "svip"
    rank == 2 || path.contains("green", true) || path.contains("luxury", true) || label.contains("绿钻") -> "green_diamond"
    rank == 1 || label.isNotBlank() -> "vip"
    else -> ""
}

private fun rankForMembership(path: String, label: String, typeCode: Int?, superFlag: Boolean): Int = when {
    superFlag || path.contains("svip", true) || label.contains("超级") || label.contains("SVIP", true) -> 3
    typeCode != null && typeCode >= 11 -> 3
    path.contains("green", true) || path.contains("luxury", true) || label.contains("绿钻") -> 2
    typeCode != null && typeCode >= 2 -> 2
    label.contains("会员") || label.contains("vip", true) || typeCode == 1 -> 1
    else -> 0
}

private fun avatarPreferenceScore(value: String): Int {
    val url = value.lowercase()
    return value.length + when {
        "thirdwx.qlogo.cn" in url || "wx.qlogo.cn" in url -> 2_000
        "qlogo.cn" in url -> 1_500
        "default" in url || "placeholder" in url -> -2_000
        else -> 0
    }
}

internal fun parseUserProfile(root: JsonElement): UserProfile? {
    fun objects(value: JsonElement, path: String = "root"): Sequence<Pair<String, JsonObject>> = sequence {
        when (value) {
            is JsonObject -> {
                yield(path to value)
                value.forEach { (key, child) -> yieldAll(objects(child, "$path.$key")) }
            }
            is JsonArray -> value.forEachIndexed { index, child -> yieldAll(objects(child, "$path[$index]")) }
            else -> Unit
        }
    }
    val all = objects(root).toList()
    fun JsonObject.value(vararg names: String) = names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    fun JsonObject.values(vararg names: String): List<String> = names.mapNotNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    val identity = all.firstOrNull { (_, item) -> item.value("nick", "nickname", "nickName", "userName") != null }?.second
    val name = identity?.value("nick", "nickname", "nickName", "userName").orEmpty()
    val avatarKeys = arrayOf(
        "logo", "logoUrl", "headurl", "headUrl", "headpic", "headPic", "head_pic", "headPicUrl",
        "headimgurl", "headImgUrl", "avatar", "avatarurl", "avatarUrl", "portrait",
    )
    val avatar = buildList {
        identity?.value(*avatarKeys)?.let(::add)
        all.mapNotNullTo(this) { (_, item) -> item.value(*avatarKeys) }
    }.asSequence().map(::normalizeHttpsUrl).filter(String::isNotBlank).maxByOrNull(::avatarPreferenceScore).orEmpty()
    val now = System.currentTimeMillis() / 1000
    val memberships = all.mapNotNull { (path, item) ->
        val rawStatuses = item.values(*membershipStatusKeys).mapNotNull(::membershipBoolean)
        val rawTypes = item.values(*membershipTypeKeys).mapNotNull { it.toIntOrNull() }
        val superFlag = item.values("isSVip", "is_svip", "isSvip", "svip", "HugeVip", "svipFlag", "svip_flag", "svip_status")
            .mapNotNull(::membershipBoolean).any { it }
        val labelRaw = item.value("vipName", "vip_name", "vipLevelName", "levelName", "svipName", "name", "title").orEmpty()
        val pathAndLabel = "$path $labelRaw"
        val expiry = item.values(*membershipExpiryKeys).mapNotNull(::profileEpoch).maxOrNull()
        val typeCode = rawTypes.maxOrNull()
        val rank = rankForMembership(path, labelRaw, typeCode, superFlag)
        val label = when {
            pathAndLabel.contains("听书", true) || pathAndLabel.contains("book", true) -> "听书会员"
            rank >= 3 -> "超级会员（SVIP）"
            pathAndLabel.contains("绿钻", true) || pathAndLabel.contains("green", true) || pathAndLabel.contains("luxury", true) || rank == 2 -> "豪华绿钻"
            labelRaw.contains("会员") || labelRaw.contains("vip", true) -> labelRaw
            else -> ""
        }
        val pathParts = path.lowercase().replace('[', '.').replace(']', '.').split('.')
        val songContext = pathParts.any { it in setOf("song", "track", "songinfo", "trackinfo", "songlist", "searchsong") }
        val pathMembership = path.contains("vip", true) || path.contains("member", true) ||
            path.contains("identity", true) || path.contains("userinfo", true)
        val rootMembership = path == "root" && (rawStatuses.isNotEmpty() || rawTypes.isNotEmpty() || expiry != null)
        val membershipPath = !songContext && (pathMembership || rootMembership)
        val hasMembershipSignal = membershipPath || (!songContext && (rawStatuses.isNotEmpty() || rawTypes.isNotEmpty() || expiry != null || label.isNotBlank()))
        if (!hasMembershipSignal) null else {
            val enabled = when {
                rawStatuses.any { it } -> true
                rawStatuses.any { !it } -> false
                rawTypes.any { it > 0 } -> true
                rawTypes.isNotEmpty() -> false
                else -> null
            }
            MembershipEvidence(enabled, expiry, label, membershipTypeFrom(rank, path, label), rank)
        }
    }
    val active = memberships.filter { evidence ->
        when {
            evidence.enabled == true -> evidence.expiry == null || evidence.expiry > now
            evidence.enabled == false -> false
            else -> evidence.expiry?.let { it > now } == true
        }
    }.maxWithOrNull(compareBy<MembershipEvidence> { it.rank }.thenBy { it.expiry ?: 0L })
    val known = memberships.maxWithOrNull(compareBy<MembershipEvidence> { it.rank }.thenBy { it.expiry ?: 0L })
    val expire = active?.expiry ?: memberships.mapNotNull(MembershipEvidence::expiry).maxOrNull()
    val isVip = when {
        active != null -> true
        memberships.isNotEmpty() -> false
        else -> null
    }
    val chosen = active ?: known
    val vipName = chosen?.label.orEmpty().ifBlank { if (active != null) "QQ 音乐会员" else "" }
    val provisional = UserProfile(
        displayName = name, avatarUrl = avatar, isVip = isVip, vipExpireAt = expire, vipName = vipName,
        vipType = chosen?.type.orEmpty(), vipLevel = chosen?.rank ?: 0,
    )
    return provisional.copy(qualityEntitlements = profileQualityOptions(provisional, now * 1_000L))
        .takeIf { it.displayName.isNotBlank() || it.avatarUrl.isNotBlank() || it.isVip != null || it.vipExpireAt != null }
}

private fun membershipRank(name: String): Int = when {
    name.contains("SVIP", true) || name.contains("\u8d85\u7ea7") -> 3
    name.contains("\u7eff\u94bb") -> 2
    name.contains("\u4f1a\u5458") || name.isNotBlank() -> 1
    else -> 0
}

/**
 * QQ Music has returned this value as seconds, milliseconds, compact dates,
 * and both local/ISO date strings over time. Keep the conversion in one place
 * and reject counters such as `userinfo.expire=9`.
 */
internal fun profileEpoch(value: String): Long? {
    val text = value.trim()
    if (text.isBlank()) return null

    fun parse(pattern: String, input: String, timezone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
            isLenient = false
            timeZone = timezone
        }
        val position = java.text.ParsePosition(0)
        val parsed = format.parse(input, position) ?: return null
        return parsed.time.div(1_000L).takeIf { position.index == input.length && it >= 946_684_800L }
    }

    // Compact calendar values need to be checked before treating them as an epoch.
    if (text.length == 14 && text.startsWith("20") && text.all(Char::isDigit)) {
        parse("yyyyMMddHHmmss", text)?.let { return it }
    }
    if (text.length == 8 && text.startsWith("20") && text.all(Char::isDigit)) {
        parse("yyyyMMdd", text)?.let { return it }
    }

    text.toLongOrNull()?.let { raw ->
        val seconds = when {
            raw >= 100_000_000_000_000_000L -> raw / 1_000_000_000L
            raw >= 100_000_000_000_000L -> raw / 1_000_000L
            raw >= 100_000_000_000L -> raw / 1_000L
            else -> raw
        }
        if (seconds >= 946_684_800L) return seconds
    }

    // Keep timestamp precision while retaining the device-local interpretation
    // for date-only values.
    val normalizedFraction = Regex("(\\.\\d{3})\\d+").replace(text) { it.value.take(4) }
    val timezonePatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss.SSSXXX", "yyyy-MM-dd HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
    )
    timezonePatterns.firstNotNullOfOrNull { pattern -> parse(pattern, normalizedFraction) }?.let { return it }
    listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss.SSS", "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd", "yyyy/MM/dd",
    ).firstNotNullOfOrNull { pattern -> parse(pattern, normalizedFraction) }?.let { return it }
    return null
}

internal fun mergeUserProfiles(values: List<UserProfile>): UserProfile? {
    if (values.isEmpty()) return null
    val nowMillis = System.currentTimeMillis()
    val normalized = values.map(::normalizeUserProfile)
    val active = normalized.filter { it.isVipActive(nowMillis) }
        .maxWithOrNull(compareBy<UserProfile> { maxOf(it.vipLevel, membershipRank(it.vipName)) }.thenBy { it.vipExpireAt ?: 0L })
    val known = normalized.maxWithOrNull(compareBy<UserProfile> { maxOf(it.vipLevel, membershipRank(it.vipName)) }.thenBy { it.vipExpireAt ?: 0L })
    val hasMembershipSignal = normalized.any { it.isVip != null || it.vipExpireAt != null || it.vipName.isNotBlank() }
    val isVip = when {
        active != null -> true
        hasMembershipSignal -> false
        else -> null
    }
    val expire = active?.vipExpireAt ?: normalized.mapNotNull(UserProfile::vipExpireAt).maxOrNull()
    val chosen = active ?: known
    val merged = UserProfile(
        displayName = normalized.firstNotNullOfOrNull { it.displayName.takeIf(String::isNotBlank) }.orEmpty(),
        avatarUrl = normalized.map(UserProfile::avatarUrl).filter(String::isNotBlank).maxByOrNull(::avatarPreferenceScore).orEmpty(),
        isVip = isVip,
        vipExpireAt = expire,
        vipName = chosen?.vipName.orEmpty(),
        vipType = chosen?.vipType.orEmpty(),
        vipLevel = chosen?.vipLevel ?: 0,
    )
    return merged.copy(qualityEntitlements = profileQualityOptions(merged, nowMillis))
}

internal fun isVersionNewer(latest: String, current: String): Boolean {
    fun parts(value: String) = value.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val left = parts(latest); val right = parts(current)
    return (0 until maxOf(left.size, right.size)).firstNotNullOfOrNull { index ->
        val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        difference.takeIf { it != 0 }
    }?.let { it > 0 } ?: false
}

internal fun parseGitHubRelease(root: JsonObject, currentVersion: String): ReleaseInfo {
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
    val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
        asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
    }
    val apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty()
        .takeIf { it.startsWith("https://github.com/huanghao897/QMusicWatch/releases/download/") }.orEmpty()
    val digest = apk?.get("digest")?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("sha256:")
    val bodyDigest = Regex("(?i)sha-?256\\s*[:=]\\s*([a-f0-9]{64})").find(notes)?.groupValues?.getOrNull(1).orEmpty()
    return ReleaseInfo(
        tag = tag, title = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { tag }, notes = notes,
        pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty().takeIf { it.startsWith("https://github.com/huanghao897/QMusicWatch/releases/") }.orEmpty(),
        apkUrl = apkUrl, sha256 = digest.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: bodyDigest,
        newer = isVersionNewer(tag, currentVersion),
    )
}

private fun parseSearchTrackItem(item: JsonObject): Track? {
    fun text(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun number(name: String) = (item[name] as? JsonPrimitive)?.longOrNull ?: 0
    val file = item["file"] as? JsonObject ?: JsonObject(emptyMap())
    fun fileText(name: String) = (file[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun fileNumber(name: String) = (file[name] as? JsonPrimitive)?.longOrNull ?: 0
    val mid = firstUsableQqSongMid(text("songmid"), text("mid"), text("song_mid"))
    val title = text("songname").ifBlank { text("title") }
    if (!isUsableQqSongMid(mid) || title.isBlank()) return null
    val pay = item["pay"] as? JsonObject ?: JsonObject(emptyMap())
    fun pay(name: String) = (pay[name] as? JsonPrimitive)?.intOrNull ?: 0
    val album = item["album"] as? JsonObject ?: JsonObject(emptyMap())
    val albumMid = text("albummid").ifBlank { (album["mid"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    val albumName = text("albumname").ifBlank { (album["title"] as? JsonPrimitive)?.contentOrNull.orEmpty() }.ifBlank { (album["name"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    return Track(mid, title, (item["singer"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }, albumName, qmusicAlbumArtworkUrl(albumMid).ifBlank { qmusicSongArtworkUrl(mid) }, true, parseQqQualityIds(item, file), numericId = number("songid").takeIf { it > 0 } ?: number("id"), mediaMid = fileText("media_mid"), songType = number("type").toInt(), requiresVip = text("isonly") == "1" || pay("payplay") != 0 || pay("pay_play") != 0)
}

internal fun parseSearchTrack(item: JsonObject): Track? =
    parseSearchTrackItem(item) ?: (item["grp"] as? JsonArray)
        .orEmpty()
        .firstNotNullOfOrNull { (it as? JsonObject)?.let(::parseSearchTrackItem) }

internal fun nextSearchCursor(page: Int, rawItemCount: Int, pageSize: Int = 20): String? =
    (page + 1).toString().takeIf { rawItemCount >= pageSize }

internal fun playlistDirectoryNumber(value: String): Long =
    value.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("歌单目录标识无效")

internal data class QqPlaylistTrackWrite(
    val module: String,
    val method: String,
    val param: JsonObject,
)

internal data class QqPlaylistDetailIdentity(
    val dissId: Long,
    val directoryId: Long,
)

internal fun qqPlaylistDetailIdentity(collection: MusicCollection): QqPlaylistDetailIdentity {
    val directoryId = collection.directoryId.toLongOrNull()?.takeIf { it > 0 } ?: 0
    val dissId = collection.id.toLongOrNull()?.takeIf { it > 0 } ?: 0
    return if (collection.owned == true && directoryId > 0) {
        QqPlaylistDetailIdentity(dissId = 0, directoryId = directoryId)
    } else {
        QqPlaylistDetailIdentity(dissId = dissId, directoryId = 0)
    }
}

internal fun qqPlaylistTrackWrite(
    directoryId: Long,
    track: Track,
    add: Boolean,
): QqPlaylistTrackWrite {
    require(directoryId > 0) { "歌单目录标识无效" }
    require(track.numericId > 0) { "歌曲数字标识无效" }
    return QqPlaylistTrackWrite(
        module = "music.musicasset.PlaylistDetailWrite",
        method = if (add) "AddSonglist" else "DelSonglist",
        param = buildJsonObject {
            put("dirId", directoryId)
            put("tid", 0)
            put("bFmtUtf8", true)
            putJsonArray("v_songInfo") {
                addJsonObject {
                    put("songId", track.numericId)
                    put("songType", track.songType)
                }
            }
        },
    )
}

internal fun qqFavoriteTrackWrite(track: Track, liked: Boolean): QqPlaylistTrackWrite {
    val songMid = track.id.trim()
    require(isUsableQqSongMid(songMid)) { "歌曲 MID 无效" }
    require(track.numericId > 0) { "歌曲数字标识无效" }
    return QqPlaylistTrackWrite(
        module = "music.musicasset.SongFavWrite",
        method = if (liked) "AddSongFav" else "DeleteSongFav",
        param = buildJsonObject {
            put("v_songMid", songMid)
            put("v_songId", track.numericId.toString())
        },
    )
}

internal fun qqFavoritePlaylistFallback(track: Track, liked: Boolean): QqPlaylistTrackWrite =
    qqPlaylistTrackWrite(201L, track.copy(songType = 0), liked)

internal fun qqFavoriteComm(accountId: String): JsonObject = buildJsonObject {
    require(accountId.matches(Regex("\\d{1,24}"))) { "账号标识无效" }
    put("ct", 19)
    put("cv", 1845)
    put("uin", accountId)
}

internal fun qqWriteBusinessCode(data: JsonObject): Int? =
    sequenceOf("retCode", "retcode")
        .mapNotNull { name -> data[name]?.jsonPrimitive?.intOrNull }
        .firstOrNull()

internal fun qqMusicuPayload(
    comm: JsonObject,
    module: String,
    method: String,
    param: JsonObject,
    requestKey: String = "req_0",
): JsonObject = buildJsonObject {
    put("comm", comm)
    putJsonObject(requestKey) {
        put("module", module)
        put("method", method)
        put("param", param)
    }
}

/**
 * QQ Music client. Post-login requests go from the watch directly to official
 * QQ Music hosts. The Ronan server is used only by ControlPlaneClient and QR login.
 */
class ApiClient(
    context: Context,
    private val cookie: () -> String?,
    private val updateCookie: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val prefs = context.getSharedPreferences("qq_direct_api", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val credentialRefreshLock = Any()
    private data class CachedStream(val data: StreamData, val generation: Long)
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val streamGenerations = ConcurrentHashMap<String, Long>()
    @Volatile private var verifiedCredentialCookie = ""
    @Volatile private var credentialVerifiedUntil = 0L
    @Volatile private var recentlyRefreshedCookie = ""
    @Volatile private var recentlyRefreshedAt = 0L

    private val guid = saved("guid") { (random.nextLong().ushr(1) % 10_000_000_000L).toString() }
    private val refreshWid = saved("refresh_wid") { random.nextLong().ushr(1).toString() }
    private val refreshDeviceName = saved("refresh_device_name") {
        "QMusicWatch-${random.nextLong().ushr(1).toString(16).takeLast(8).uppercase()}"
    }

    suspend fun refreshCredential(provider: String): Boolean = withContext(Dispatchers.IO) {
        require(provider in setOf("qq", "wechat")) { "不支持的登录方式" }
        val staleCookie = cookie().orEmpty()
        if (staleCookie.isBlank()) return@withContext false
        refreshCredentialBlocking(staleCookie, provider)
    }

    suspend fun home(): HomeData = withContext(Dispatchers.IO) {
        coroutineScope {
            val personalizedTask = async {
                if (cookie().isNullOrBlank()) emptyList() else runCatching {
                    api(
                        "music.radioProxy.MbTrackRadioSvr", "get_radio_track",
                        obj("id" to 99, "num" to 20, "from" to 0, "scene" to 0, "song_ids" to emptyList<Long>()),
                    ).let(::findTracks)
                }.onFailure {
                    AppLog.write("HOME", "personalized ${it.javaClass.simpleName}:${it.message.orEmpty()}")
                }.getOrDefault(emptyList())
            }
            val fallbackTask = async {
                runCatching {
                    api("newsong.NewSongServer", "get_new_song_info", obj("type" to 5)).let(::findTracks)
                }.onFailure {
                    AppLog.write("HOME", "fallback ${it.javaClass.simpleName}:${it.message.orEmpty()}")
                }.getOrDefault(emptyList())
            }
            val personalized = personalizedTask.await()
            val fallback = fallbackTask.await()
            val daily = if (personalized.size >= 20) personalized else {
                (personalized + fallback).distinctBy(Track::id)
            }
            HomeData(daily, emptyList())
        }
    }

    suspend fun searchTracks(query: String, cursor: String? = null): PagedTracks = withContext(Dispatchers.IO) {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val items = webSearch(query, page, 0)["song"]?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        val searchObjects = items.mapNotNull { it as? JsonObject }
        val parsedTracks = searchObjects.mapNotNull(::parseSearchTrack)
        val directTracks = parsedTracks.distinctBy(Track::id)
        val rejected = searchObjects.size - parsedTracks.size
        val structuredItems = if (rejected > 0) runCatching {
            val data = api(
                "music.search.SearchCgiService", "DoSearchForQQMusicDesktop",
                obj("remoteplace" to "txt.yqq.center", "search_type" to 0, "query" to query, "page_num" to page, "num_per_page" to 20, "grp" to 1),
            )
            data["body"]?.jsonObject?.get("song")?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        }.onFailure { error ->
            AppLog.write("SEARCH", "structured fallback ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        }.getOrDefault(JsonArray(emptyList())) else JsonArray(emptyList())
        val structuredTracks = structuredItems.mapNotNull { it as? JsonObject }.mapNotNull(::parseSearchTrack).distinctBy(Track::id)
        val tracks = if (structuredTracks.size > directTracks.size) structuredTracks else directTracks
        if (rejected > 0) AppLog.write(
            "SEARCH",
            "tracks page=$page ignored=$rejected fallback=${structuredTracks.size} returned=${tracks.size}",
        )
        PagedTracks(tracks, nextSearchCursor(page, maxOf(items.size, structuredItems.size)))
    }

    suspend fun searchCollections(type: String, query: String, cursor: String? = null): PagedCollections = withContext(Dispatchers.IO) {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val items = if (type == "album") {
            webSearch(query, page, 8)["album"]?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        } else {
            val searchType = if (type == "artist") 1 else 3
            val data = api("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", obj("remoteplace" to "txt.yqq.center", "search_type" to searchType, "query" to query, "page_num" to page, "num_per_page" to 20, "grp" to 1))
            val key = if (type == "artist") "singer" else "songlist"
            data["body"]?.jsonObject?.get(key)?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        }
        var collections = items.mapNotNull { it as? JsonObject }.mapNotNull { searchCollection(type, it) }
        if (collections.isEmpty() && page == 1) {
            val key = if (type == "artist") "singer" else "songlist"
            val suggestions = smartSearch(query)[key]?.jsonObject?.get("itemlist") as? JsonArray ?: JsonArray(emptyList())
            collections = suggestions.mapNotNull { it as? JsonObject }.mapNotNull { searchCollection(type, it) }
        }
        PagedCollections(collections, nextSearchCursor(page, items.size))
    }

    suspend fun lyrics(id: String): LyricsData = withContext(Dispatchers.IO) {
        coroutineScope {
            val textTask = async {
                webApi(
                    "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
                    obj("songMid" to id, "crypt" to 0, "qrc" to 0, "qrc_t" to 0, "trans" to 1, "trans_t" to 0, "roma" to 0, "roma_t" to 0, "type" to 1, "ct" to 24, "cv" to 4_747_474),
                )
            }
            val qrcTask = async {
                runCatching {
                    webApi(
                        "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
                        obj("songMid" to id, "crypt" to 1, "qrc" to 1, "qrc_t" to 0, "trans" to 0, "trans_t" to 0, "roma" to 0, "roma_t" to 0, "type" to 1, "ct" to 24, "cv" to 4_747_474),
                        callTimeoutMs = 2_500,
                    ).string("lyric").takeIf { it.isNotBlank() }?.let(QqQrcDecoder::decode)
                }.onFailure {
                    AppLog.write("LYRICS", "qrc ${it.javaClass.simpleName}:${it.message.orEmpty()}")
                }.getOrNull()
            }
            val data = textTask.await()
            val qrc = qrcTask.await()
            LyricsData(
                decodeText(data.string("lyric")),
                decodeText(data.string("trans")).ifBlank { null },
                qrc,
            )
        }
    }

    suspend fun stream(track: Track, quality: String): StreamData =
        stream(track, quality, allowCredentialRecovery = true, allowCached = true)

    fun invalidateStream(trackId: String) {
        val account = accountId()
        val scopeKey = "$account:$trackId"
        streamGenerations.merge(scopeKey, 1L, Long::plus)
        streamCache.keys.removeIf { key ->
            key.startsWith("$scopeKey:")
        }
        AppLog.write("STREAM", "cache-invalidated track=$trackId")
    }

    suspend fun refreshStream(track: Track, quality: String): StreamData {
        invalidateStream(track.id)
        return stream(track, quality, allowCredentialRecovery = true, allowCached = false)
    }

    private suspend fun stream(
        track: Track,
        quality: String,
        allowCredentialRecovery: Boolean,
        allowCached: Boolean,
    ): StreamData = withContext(Dispatchers.IO) {
        if (!isUsableQqSongMid(track.id)) {
            AppLog.write("STREAM", "blocked invalid track id")
            error("歌曲信息已失效，请重新搜索后播放")
        }
        requireLogin()
        val preferred = normalizeQualityId(quality)
        val scopeKey = "${accountId()}:${track.id}"
        val generation = streamGenerations.getOrPut(scopeKey) { 0L }
        val cacheKey = "$scopeKey:$preferred"
        streamCache[cacheKey]?.takeIf {
            allowCached &&
                it.generation == generation &&
                it.data.expiresAt > System.currentTimeMillis() + 30_000L &&
                trustedQMusicMediaUrl(it.data.url).isNotBlank()
        }?.data?.let {
            AppLog.write("STREAM", "cache-hit track=${track.id} quality=${it.quality}")
            return@withContext it
        }
        AppLog.write("STREAM", "request track=${track.id} preferred=$preferred vip=${track.requiresVip}")
        val complete = if (track.mediaMid.isBlank()) trackDetail(track.id) else track
        val qualities = qualityFallbackOrder(preferred)
        var firstFailure: Throwable? = null
        var receivedResponse = false
        var bestFallback: StreamData? = null
        qualities.forEachIndexed { qualityIndex, requested ->
            val filename = qqStreamFileName(requested, complete.mediaMid)
            val param = obj(
                "uin" to accountId(), "filename" to listOf(filename), "guid" to guid,
                "songmid" to listOf(complete.id), "songtype" to listOf(complete.songType),
                "loginflag" to 1, "platform" to "20", "ctx" to 0
            )
            listOf(
                Triple(playbackComm(android = false), "vkey.GetVkeyServer", "CgiGetVkey"),
                Triple(playbackComm(android = true), "music.vkey.GetVkey", "UrlGetVkey"),
            ).forEach attempt@{ (comm, module, method) ->
                val data = try {
                    post(comm, module, method, param, tolerateBusinessError = true)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (error is QqCredentialExpiredException) throw error
                    if (firstFailure == null) firstFailure = error
                    AppLog.write("STREAM", "attempt=$module failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    return@attempt
                }
                receivedResponse = true
                val path = streamPath(data) ?: return@attempt
                if (path.value.isNotBlank()) {
                    val bases = walkObjects(data).flatMap { item ->
                        (item["sip"] as? JsonArray).orEmpty().asSequence()
                    }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .filter(String::isNotBlank)
                        .distinct()
                        .toList()
                    val url = resolveQqStreamUrl(path.value, bases)
                    if (url.isBlank()) error("音乐服务器返回了不受信任的播放地址")
                    val actual = inferQqStreamQuality(path.value, path.sourceKey)
                    val stream = StreamData(url, actual, System.currentTimeMillis() + data.long("expiration", 3600) * 1000)
                    AppLog.write("STREAM", "issued requested=$requested actual=$actual via=$module")
                    bestFallback = higherQualityStream(bestFallback, stream)
                    if (actual == requested) {
                        ensureCurrentStreamGeneration(scopeKey, generation)
                        cacheStream(cacheKey, scopeKey, generation, bestFallback!!)
                        return@withContext bestFallback!!
                    }
                }
            }
            if (shouldProbePlaybackCredential(qualityIndex, bestFallback != null, allowCredentialRecovery) &&
                probePlaybackCredential()
            ) {
                AppLog.write("STREAM", "credential refreshed; retry track=${track.id}")
                return@withContext stream(
                    track,
                    quality,
                    allowCredentialRecovery = false,
                    allowCached = allowCached,
                )
            }
        }
        bestFallback?.let {
            ensureCurrentStreamGeneration(scopeKey, generation)
            cacheStream(cacheKey, scopeKey, generation, it)
            return@withContext it
        }
        if (!receivedResponse) firstFailure?.let { throw it }
        AppLog.write("STREAM", "no-url track=${track.id} vip=${complete.requiresVip}")
        error(if (complete.requiresVip) "这首歌需要 VIP 或购买" else "QQ 音乐未提供播放地址，可能存在版权、地区或账号权益限制")
    }

    private fun ensureCurrentStreamGeneration(scopeKey: String, generation: Long) {
        if (!isCurrentStreamGeneration(generation, streamGenerations[scopeKey])) {
            throw CancellationException("stream request superseded")
        }
    }

    private fun cacheStream(
        cacheKey: String,
        scopeKey: String,
        generation: Long,
        stream: StreamData,
    ) {
        if (streamGenerations[scopeKey] == generation) {
            streamCache[cacheKey] = CachedStream(stream, generation)
        }
    }

    suspend fun library(): LibraryData = withContext(Dispatchers.IO) {
        requireLogin()
        coroutineScope {
            val playlistsTask = async {
                api("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", obj("uin" to accountId()))
            }
            val collectedTask = async {
                runCatching { favoritePlaylists() }.onFailure { error ->
                    val detail = (error as? QqBusinessException)?.let { "business_code=${it.businessCode}" }
                        ?: "${error.javaClass.simpleName}"
                    AppLog.write("LIBRARY", "favorite playlists unavailable $detail")
                }.getOrDefault(emptyList())
            }
            val likedTask = async { likedTracks() }
            val playlists = playlistsTask.await()
            val collectedPlaylists = collectedTask.await()
            val likedTracks = likedTask.await()
            val accountPlaylists = parseAccountPlaylists(playlists)
            normalizeLibraryData(
                LibraryData(likedTracks, mergeLibraryPlaylists(accountPlaylists, collectedPlaylists)),
            )
        }
    }

    private suspend fun likedTracks(): List<Track> {
        val likedTracks = mutableListOf<Track>()
        val likedIds = mutableSetOf<String>()
        for (page in 0 until 20) {
            val liked = api(
                "music.srfDissInfo.DissInfo", "CgiGetDiss",
                obj("disstid" to 0, "dirid" to 201, "tag" to true, "song_begin" to page * 100, "song_num" to 100, "userinfo" to true, "orderlist" to true),
            )
            val batch = findTracks(liked)
            val previousSize = likedTracks.size
            likedTracks += batch.filter { likedIds.add(it.id) }
            if (batch.size < 100 || likedTracks.size == previousSize) break
        }
        return likedTracks
    }

    private suspend fun favoritePlaylists(): List<MusicCollection> {
        val encryptedUin = cookieValue("euin").orEmpty()
        if (encryptedUin.isBlank()) {
            AppLog.write("LIBRARY", "favorite playlists encrypted_id=missing compatibility=unpaged")
            return api(
                "music.musicasset.PlaylistFavRead", "GetPlaylistFavInfo",
                obj("uin" to accountId()),
            ).let(::parseFavoritePlaylists)
        }

        val playlists = mutableListOf<MusicCollection>()
        val directories = mutableSetOf<String>()
        for (page in 0 until 20) {
            val data = try {
                api(
                    "music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                    obj("uin" to encryptedUin, "offset" to page * 100, "size" to 100),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (page == 0 && error is QqBusinessException && error.businessCode == 80050) {
                    AppLog.write("LIBRARY", "favorite playlists compatibility=unpaged")
                    return api(
                        "music.musicasset.PlaylistFavRead", "GetPlaylistFavInfo",
                        obj("uin" to accountId()),
                    ).let(::parseFavoritePlaylists)
                }
                if (playlists.isNotEmpty()) {
                    val detail = (error as? QqBusinessException)?.let { "business_code=${it.businessCode}" }
                        ?: error.javaClass.simpleName
                    AppLog.write("LIBRARY", "favorite playlists partial $detail")
                    return playlists
                }
                throw error
            }
            val batch = parseFavoritePlaylists(data)
            val previousSize = playlists.size
            playlists += batch.filter { directories.add(it.directoryId) }
            if (batch.size < 100 || playlists.size == previousSize) break
        }
        return playlists
    }

    suspend fun profile(): UserProfile = withContext(Dispatchers.IO) {
        requireLogin()
        val id = accountId()
        val roots = coroutineScope {
            val profileTasks = listOf("GetLoginUserInfo", "GetUserInfo").map { method ->
                async {
                    runCatching {
                        api(
                            "music.UserInfo.userInfoServer",
                            method,
                            obj("user_uin" to id, "login_uin" to id, "uin" to id),
                        )
                    }.getOrNull()?.also { data ->
                        AppLog.write("PROFILE", "$method keys=${data.keys.joinToString(",").take(300)}")
                    }
                }
            }
            val vipTask = async {
                runCatching {
                    api("VipLogin.VipLoginInter", "vip_login_base", obj())
                }.getOrNull()?.let { data ->
                    AppLog.write("PROFILE", "vip_login_base keys=${data.keys.joinToString(",").take(300)}")
                    buildJsonObject { put("vip_response", data) }
                }
            }
            val legacyTask = async { runCatching { legacyProfile() }.getOrNull() }
            (profileTasks + listOf(vipTask, legacyTask)).awaitAll().filterNotNull()
        }
        mergeUserProfiles(roots.mapNotNull(::parseUserProfile)) ?: error("QQ 音乐未返回账号资料")
    }

    suspend fun latestRelease(currentVersion: String): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/huanghao897/QMusicWatch/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "QMusicWatch/$currentVersion").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext ReleaseInfo(
                tag = currentVersion, title = "暂无正式发布版本", notes = "当前仓库尚未创建 GitHub Release。",
                pageUrl = "https://github.com/huanghao897/QMusicWatch/releases", newer = false,
            )
            if (!response.isSuccessful) error("GitHub Release 检查失败 ${response.code}")
            parseGitHubRelease(json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject, currentVersion)
        }
    }

    suspend fun recent(): List<Track> = emptyList() // QQ Music has no stable public recent-play contract.

    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        requireLogin()
        val library = library()
        val track = home().daily.firstOrNull { !it.requiresVip && it.playable }
            ?: error("没有找到可用于播放诊断的免费歌曲")
        val stream = stream(track, QUALITY_STANDARD)
        val request = Request.Builder().url(stream.url)
            .withQqMusicMediaHeaders()
            .header("Range", "bytes=0-0").build()
        http.newCall(request).execute().use { response ->
            AppLog.write("DIAG", "cdn status=${response.code}")
            if (response.code !in listOf(200, 206)) error("播放 CDN 响应 ${response.code}")
            "登录有效 · ${library.playlists.size} 个歌单 · 播放 CDN ${response.code}"
        }
    }

    suspend fun like(track: Track, liked: Boolean): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        val complete = if (track.numericId > 0) track else trackDetail(track.id)
        if (complete.numericId <= 0) error("QQ 音乐未返回歌曲数字标识，无法修改喜欢状态")
        val authCookie = normalizedAuthCookie()
        val primary = qqFavoriteTrackWrite(complete, liked)
        try {
            requireWriteAccepted(
                post(
                    qqFavoriteComm(accountId()),
                    primary.module,
                    primary.method,
                    primary.param,
                    requestCookie = authCookie,
                )
            )
        } catch (error: QqBusinessException) {
            if (error.businessCode !in setOf(80105, 500003)) throw error
            AppLog.write("FAVORITE", "primary rejected code=${error.businessCode}; fallback=playlist201")
            val fallback = qqFavoritePlaylistFallback(complete, liked)
            requireWriteAccepted(
                post(
                    playbackComm(android = true),
                    fallback.module,
                    fallback.method,
                    fallback.param,
                    requestCookie = authCookie,
                )
            )
        }
        Ack(true)
    }

    suspend fun collection(type: String, collection: MusicCollection): CollectionDetail = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val ids = mutableSetOf<String>()
        var title = collection.title.ifBlank { "详情" }
        for (page in 0 until 20) {
            val begin = page * 100
            val data = when (type) {
                "album" -> api("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", obj("albumMid" to collection.id, "begin" to begin, "num" to 100, "order" to 2))
                "artist" -> api("musichall.song_list_server", "GetSingerSongList", obj("singerMid" to collection.id, "begin" to begin, "num" to 100, "order" to 1))
                else -> {
                    val identity = qqPlaylistDetailIdentity(collection)
                    if (identity.dissId <= 0 && identity.directoryId <= 0) error("歌单标识无效，请刷新歌单后重试")
                    api(
                        "music.srfDissInfo.DissInfo", "CgiGetDiss",
                        obj(
                            "disstid" to identity.dissId, "dirid" to identity.directoryId,
                            "tag" to true, "song_begin" to begin, "song_num" to 100,
                            "userinfo" to true, "orderlist" to true,
                        ),
                    )
                }
            }
            if (page == 0) title = walkObjects(data).firstNotNullOfOrNull { it.string("title").ifBlank { it.string("name") }.takeIf(String::isNotBlank) } ?: title
            val batch = findTracks(data)
            val previousSize = tracks.size
            tracks += batch.filter { ids.add(it.id) }
            if (batch.size < 100 || tracks.size == previousSize) break
        }
        CollectionDetail(title, tracks)
    }

    suspend fun createPlaylist(title: String): MusicCollection = withContext(Dispatchers.IO) {
        requireLogin()
        val data = writeApi("music.musicasset.PlaylistBaseWrite", "AddPlaylist", obj("dirName" to title))
        val item = walkObjects(data).firstOrNull { it["dirId"] != null || it["tid"] != null } ?: JsonObject(emptyMap())
        val dirId = item.string("dirId")
        playlistDirectoryNumber(dirId)
        MusicCollection(item.string("tid").ifBlank { dirId }, title, directoryId = dirId)
    }

    suspend fun renamePlaylist(id: String, title: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        writeApi("music.musicasset.PlaylistBaseWrite", "ModifyPlaylist", obj("dirId" to playlistDirectoryNumber(id), "dirName" to title))
        Ack(true)
    }

    suspend fun deletePlaylist(id: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        writeApi("music.musicasset.PlaylistBaseWrite", "DelPlaylist", obj("dirId" to playlistDirectoryNumber(id)))
        Ack(true)
    }

    suspend fun changePlaylistTrack(id: String, track: Track, add: Boolean): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        val complete = if (track.numericId > 0) track else trackDetail(track.id)
        if (complete.numericId <= 0) error("QQ 音乐未返回歌曲数字标识，无法修改歌单")
        val write = qqPlaylistTrackWrite(playlistDirectoryNumber(id), complete, add)
        requireWriteAccepted(
            post(
                playbackComm(android = true),
                write.module,
                write.method,
                write.param,
                requestCookie = normalizedAuthCookie(),
            )
        )
        Ack(true)
    }

    private fun smartSearch(query: String): JsonObject {
        return legacySearch("smartSearch", query = query)["data"]?.jsonObject
            ?: error("QQ 音乐搜索响应无效")
    }

    private fun webSearch(query: String, page: Int, type: Int): JsonObject {
        return legacySearch("search", query = query, page = page, type = type)["data"]?.jsonObject
            ?: error("QQ 音乐搜索响应无效")
    }

    private fun searchCollection(type: String, item: JsonObject): MusicCollection? {
        val id = when (type) { "artist" -> item.string("singerMID").ifBlank { item.string("mid") }; "album" -> item.string("albumMID").ifBlank { item.string("mid") }; else -> item.string("dissid").ifBlank { item.string("id") }.ifBlank { item.string("mid") } }
        val title = when (type) { "artist" -> item.string("singerName").ifBlank { item.string("name") }; "album" -> item.string("albumName").ifBlank { item.string("name") }; else -> item.string("dissname").ifBlank { item.string("name") } }
        if (id.isBlank() || title.isBlank()) return null
        val count = listOf("songNum", "song_count", "songnum", "total").firstNotNullOfOrNull { key -> item[key]?.jsonPrimitive?.intOrNull } ?: -1
        return MusicCollection(id, title, normalizeHttpsUrl(item.string("singerPic").ifBlank { item.string("imgurl") }.ifBlank { item.string("pic") }), count)
    }

    private suspend fun trackDetail(mid: String): Track {
        require(isUsableQqSongMid(mid)) { "歌曲信息已失效，请重新搜索后播放" }
        val data = webApi("music.pf_song_detail_svr", "get_song_detail_yqq", obj("song_mid" to mid))
        return findTracks(data).firstOrNull() ?: error("无法读取歌曲详情")
    }

    private suspend fun api(module: String, method: String, param: JsonObject): JsonObject {
        return post(webComm(), module, method, param)
    }

    private suspend fun writeApi(module: String, method: String, param: JsonObject): JsonObject =
        post(
            playbackComm(android = true),
            module,
            method,
            param,
            requestCookie = normalizedAuthCookie(),
        )

    private suspend fun webApi(
        module: String,
        method: String,
        param: JsonObject,
        callTimeoutMs: Long? = null,
    ): JsonObject = post(webComm(), module, method, param, callTimeoutMs = callTimeoutMs)

    private fun requireWriteAccepted(data: JsonObject) {
        val code = qqWriteBusinessCode(data) ?: return
        if (code != 0) throw QqBusinessException(
            code,
            "QQ 音乐没有保存这次修改 ($code)",
        )
    }

    private fun post(
        comm: JsonObject, module: String, method: String, param: JsonObject,
        requestCookie: String? = cookie(), tolerateBusinessError: Boolean = false,
        allowCredentialRefresh: Boolean = true,
        callTimeoutMs: Long? = null,
        requestKey: String = "req_0",
        formEncodedJson: Boolean = false,
    ): JsonObject {
        val payload = qqMusicuPayload(comm, module, method, param, requestKey)
        val started = System.currentTimeMillis()
        val result = try {
            musicuRequest(payload, requestCookie, comm.int("ct") == 11, callTimeoutMs, requestKey, formEncodedJson)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (isIdempotentQqReadMethod(method) && isRecoverableQqReadFailure(error)) {
                val delayMs = qqReadRetryDelayMs(error)
                AppLog.write("API", "$module/$method transient=${error.javaClass.simpleName} retry=1 delay_ms=$delayMs")
                Thread.sleep(delayMs)
                try {
                    musicuRequest(payload, requestCookie, comm.int("ct") == 11, callTimeoutMs, requestKey, formEncodedJson)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (retryError: Throwable) {
                    AppLog.write("API", "$module/$method direct_error=${retryError.javaClass.simpleName} ms=${System.currentTimeMillis() - started}")
                    throw retryError
                }
            } else {
                AppLog.write("API", "$module/$method direct_error=${error.javaClass.simpleName} ms=${System.currentTimeMillis() - started}")
                throw error
            }
        }
        val code = result.int("code")
        AppLog.write("API", "$module/$method code=$code ms=${System.currentTimeMillis() - started}")
        if (shouldRefreshCredential(module, method, code)) {
            val staleCookie = requestCookie.orEmpty()
            val refreshed = allowCredentialRefresh && staleCookie.isNotBlank() && runCatching {
                refreshCredentialBlocking(staleCookie, MusicCookie.provider(staleCookie, "qq"))
            }.onFailure { error ->
                AppLog.write("AUTH", "credential refresh failed ${error.javaClass.simpleName}")
            }.getOrDefault(false)
            if (refreshed) {
                return post(
                    comm = requestCommAfterCredentialRefresh(module),
                    module = module,
                    method = method,
                    param = param,
                    requestCookie = cookie(),
                    tolerateBusinessError = tolerateBusinessError,
                    allowCredentialRefresh = false,
                    callTimeoutMs = callTimeoutMs,
                    requestKey = requestKey,
                    formEncodedJson = formEncodedJson,
                )
            }
            throw QqCredentialExpiredException("登录状态已失效，请重新扫码登录一次")
        }
        if (code == 0 && isLoginCredentialProbe(module, method)) {
            markCredentialVerified(requestCookie.orEmpty())
        }
        if (code != 0 && !tolerateBusinessError) throw QqBusinessException(
            code,
            result.string("msg").ifBlank { result.string("message") }
                .ifBlank { "QQ 音乐接口拒绝请求 ($code)" },
        )
        return result["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun musicuRequest(
        payload: JsonObject,
        requestCookie: String?,
        androidClient: Boolean,
        callTimeoutMs: Long?,
        requestKey: String,
        formEncodedJson: Boolean,
    ): JsonObject {
        val builder = Request.Builder()
            .url(QQ_MUSICU_URL)
            .post(payload.toString().toRequestBody(if (formEncodedJson) FORM_JSON_MEDIA else JSON_MEDIA))
            .header("Accept", "application/json")
            .header("Origin", "https://y.qq.com")
            .header("Referer", "https://y.qq.com/")
            .header(
                "User-Agent",
                when {
                    formEncodedJson -> PC_UA
                    androidClient -> "QQMusic 20030508(android ${android.os.Build.VERSION.RELEASE})"
                    else -> WEB_UA
                },
            )
        requestCookie?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
        val call = http.newCall(builder.build())
        callTimeoutMs?.takeIf { it > 0 }?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { response ->
            val body = response.body?.byteStream()?.use(::readBoundedBody).orEmpty()
            if (!response.isSuccessful) {
                throw QqHttpException(
                    response.code,
                    "QQ 音乐接口响应 ${response.code}",
                    response.header("Retry-After")?.toLongOrNull()?.times(1_000L) ?: 0L,
                )
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw QqResponseException("QQ 音乐响应格式无效", it) }
            return (root[requestKey] as? JsonObject)
                ?: (root["req_0"] as? JsonObject)
                ?: (root["req"] as? JsonObject)
                ?: throw QqResponseException("QQ 音乐响应缺少请求结果")
        }
    }

    private fun legacySearch(
        operation: String,
        query: String = "",
        page: Int = 1,
        type: Int = 0,
    ): JsonObject {
        require(operation in setOf("search", "smartSearch")) { "不支持的搜索接口" }
        require(query.isNotBlank() && query.length <= 100) { "搜索内容无效" }
        val url = "https://$QQ_LEGACY_HOST/".toHttpUrl().newBuilder().apply {
            if (operation == "search") {
                addPathSegments("soso/fcgi-bin/search_for_qq_cp")
                addQueryParameter("format", "json")
                addQueryParameter("p", page.coerceIn(1, 100).toString())
                addQueryParameter("n", "20")
                addQueryParameter("t", type.toString())
                addQueryParameter("w", query)
            } else {
                addPathSegments("splcloud/fcgi-bin/smartbox_new.fcg")
                addQueryParameter("format", "json")
                addQueryParameter("key", query)
            }
        }.build()
        return legacyGet(url, operation)
    }

    private fun legacyProfile(): JsonObject {
        val id = accountId()
        require(id.matches(Regex("\\d{1,24}"))) { "账号标识无效" }
        val gtk = hash33(cookieValue("qqmusic_key", "qm_keyst", "p_skey", "skey").orEmpty())
        val url = "https://$QQ_LEGACY_HOST/".toHttpUrl().newBuilder()
            .addPathSegments("rsc/fcgi-bin/fcg_get_profile_homepage.fcg")
            .addQueryParameter("format", "json")
            .addQueryParameter("loginUin", id)
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("userid", id)
            .addQueryParameter("g_tk", gtk.toString())
            .addQueryParameter("cid", "205360838")
            .addQueryParameter("reqfrom", "1")
            .build()
        return legacyGet(url, "profile", cookie())
    }

    private fun legacyGet(url: okhttp3.HttpUrl, operation: String, requestCookie: String? = null): JsonObject {
        fun execute(): JsonObject {
            val builder = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", WEB_UA)
            requestCookie?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.byteStream()?.use(::readBoundedBody).orEmpty()
                if (!response.isSuccessful) {
                    throw QqHttpException(
                        response.code,
                        "QQ 音乐${if (operation == "profile") "资料" else "搜索"}接口响应 ${response.code}",
                        response.header("Retry-After")?.toLongOrNull()?.times(1_000L) ?: 0L,
                    )
                }
                return runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw QqResponseException("QQ 音乐响应格式无效", it) }
            }
        }
        return try {
            execute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!isRecoverableQqReadFailure(error)) throw error
            val delayMs = qqReadRetryDelayMs(error)
            AppLog.write("API", "legacy/$operation transient=${error.javaClass.simpleName} retry=1 delay_ms=$delayMs")
            Thread.sleep(delayMs)
            execute()
        }
    }

    private fun requestCommAfterCredentialRefresh(module: String): JsonObject = when (module) {
        "vkey.GetVkeyServer" -> playbackComm(android = false)
        "music.vkey.GetVkey" -> playbackComm(android = true)
        "music.musicasset.SongFavWrite" -> qqFavoriteComm(accountId())
        "music.musicasset.PlaylistBaseWrite",
        "music.musicasset.PlaylistDetailWrite" -> playbackComm(android = true)
        else -> webComm()
    }

    private fun probePlaybackCredential(): Boolean {
        val staleCookie = cookie().orEmpty()
        if (staleCookie.isBlank()) error("请先登录")
        val now = System.currentTimeMillis()
        if (staleCookie == verifiedCredentialCookie && now < credentialVerifiedUntil) {
            AppLog.write("AUTH", "playback credential probe cache-hit")
            return false
        }
        post(
            comm = webComm(),
            module = LOGIN_USER_INFO_MODULE,
            method = LOGIN_CREDENTIAL_PROBE_METHOD,
            param = obj(),
            requestCookie = staleCookie,
        )
        val currentCookie = cookie().orEmpty()
        markCredentialVerified(currentCookie)
        return currentCookie.isNotBlank() && currentCookie != staleCookie
    }

    private fun markCredentialVerified(value: String) {
        if (value.isBlank()) return
        verifiedCredentialCookie = value
        credentialVerifiedUntil = System.currentTimeMillis() + 5 * 60_000L
    }

    private fun refreshCredentialBlocking(staleCookie: String, provider: String): Boolean =
        synchronized(credentialRefreshLock) {
            val currentCookie = cookie().orEmpty()
            if (currentCookie.isNotBlank() && currentCookie != staleCookie) return@synchronized true
            val now = System.currentTimeMillis()
            if (staleCookie == recentlyRefreshedCookie && now - recentlyRefreshedAt < 60_000L) {
                AppLog.write("AUTH", "credential refresh suppressed after recent rotation")
                return@synchronized false
            }
            val values = cookieValues(staleCookie)
            val musicId = MusicCookie.accountId(staleCookie).orEmpty()
            val musicKey = firstCookieValue(values, "qqmusic_key", "qm_keyst", "p_lskey")
            val refreshToken = values["refresh_token"].orEmpty()
            val refreshKey = values["refresh_key"].orEmpty()
            val openId = firstCookieValue(values, "wxopenid", "openid")
            if (!musicId.matches(Regex("\\d{1,24}")) || musicKey.isBlank() || refreshToken.isBlank() || openId.isBlank()) {
                throw QqCredentialExpiredException("登录状态已失效，请重新扫码登录一次")
            }
            val qqRequest = if (provider == "qq") {
                qqCredentialRefreshRequest(
                    musicId = musicId,
                    musicKey = musicKey,
                    openId = openId,
                    accessToken = values["access_token"].orEmpty(),
                    refreshToken = refreshToken,
                    refreshKey = refreshKey,
                    unionId = values["unionid"].orEmpty(),
                    expiredAt = values["expired_at"]?.toLongOrNull() ?: 0L,
                    guid = guid,
                    wid = refreshWid,
                    deviceName = refreshDeviceName,
                )
            } else {
                null
            }
            val param = qqRequest?.param ?: obj(
                    "openid" to openId,
                    "access_token" to values["access_token"].orEmpty(),
                    "refresh_token" to refreshToken,
                    "expired_in" to (values["expired_at"]?.toLongOrNull() ?: 0L),
                    "musicid" to musicId.toLong(),
                    "musickey" to musicKey,
                    "refresh_key" to refreshKey,
                    "loginMode" to 2,
                )
            val refreshComm = qqRequest?.comm ?: obj(
                "g_tk" to 5381,
                "platform" to "yqq",
                "ct" to 24,
                "cv" to 0,
                "tmeAppID" to "qqmusic",
                "tmeLoginType" to if (provider == "qq") 2 else 1,
            )
            AppLog.write("AUTH", "credential refresh contract=${if (qqRequest != null) "desktop" else "mobile"} provider=$provider")
            val data = try {
                post(
                    refreshComm,
                    "music.login.LoginServer",
                    "Login",
                    param,
                    requestCookie = null,
                    allowCredentialRefresh = false,
                    requestKey = "QQRefreshKey",
                    formEncodedJson = true,
                )
            } catch (error: QqBusinessException) {
                throw QqCredentialExpiredException("登录状态已失效，请重新扫码登录一次")
            }
            val refreshed = validateRefreshedCookie(
                staleCookie,
                buildMusicCookie(provider, values, data),
            )
            updateCookie(refreshed)
            streamCache.clear()
            verifiedCredentialCookie = ""
            credentialVerifiedUntil = 0L
            recentlyRefreshedCookie = refreshed
            recentlyRefreshedAt = System.currentTimeMillis()
            AppLog.write("AUTH", "credential refreshed provider=$provider")
            true
        }

    private fun normalizedAuthCookie(): String {
        val source = cookie().orEmpty()
        val values = cookieValues(source)
        val id = MusicCookie.accountId(source).orEmpty()
        val key = firstCookieValue(values, "qqmusic_key", "qm_keyst", "p_lskey", "p_skey", "skey")
        require(id.matches(Regex("\\d{1,24}")) && safeCookieValue(key)) { "登录凭据不完整，请重新扫码登录" }
        val loginType = values["tmeLoginType"]?.toIntOrNull()?.takeIf { it in 0..20 }
            ?: if (key.startsWith("W_X")) 1 else 2
        val normalized = linkedMapOf(
            "uin" to "o$id",
            "loginUin" to id,
            "qqmusic_uin" to id,
            "tmeLoginType" to loginType.toString(),
            "qm_keyst" to key,
            "qqmusic_key" to key,
            "musickey" to key,
        )
        passthroughCookieNames.forEach { name ->
            values[name]?.takeIf(::safeCookieValue)?.let { normalized[name] = it }
        }
        return normalized.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun buildMusicCookie(
        provider: String,
        previous: Map<String, String>,
        data: JsonObject,
    ): String {
        fun response(vararg names: String): String = names.firstNotNullOfOrNull { name ->
            data[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.orEmpty()
        val musicId = response("musicid", "str_musicid", "uin")
            .ifBlank { firstCookieValue(previous, "qqmusic_uin", "uin", "wxuin").trimStart('o') }
        val musicKey = response("musickey", "musicKey", "music_key")
            .ifBlank { firstCookieValue(previous, "qqmusic_key", "qm_keyst", "p_lskey") }
        require(musicId.matches(Regex("\\d{1,24}")) && safeCookieValue(musicKey)) {
            "QQ 音乐没有返回有效登录凭据"
        }
        val pairs = linkedMapOf(
            "login_type" to "2",
            "tmeLoginMethod" to "3",
            "uin" to "o$musicId",
            "qqmusic_uin" to musicId,
            "tmeLoginType" to if (provider == "qq") "2" else "1",
            "wxuin" to "o$musicId",
            "qm_keyst" to musicKey,
            "p_lskey" to musicKey,
            "qqmusic_key" to musicKey,
        )
        val optional = linkedMapOf(
            "euin" to response("encryptUin", "encrypt_uin").ifBlank { previous["euin"].orEmpty() },
            "wxopenid" to response("openid").ifBlank { firstCookieValue(previous, "wxopenid", "openid") },
            "unionid" to response("unionid").ifBlank { previous["unionid"].orEmpty() },
            "refresh_key" to response("refresh_key").ifBlank { previous["refresh_key"].orEmpty() },
            "refresh_token" to response("refresh_token").ifBlank { previous["refresh_token"].orEmpty() },
            "access_token" to response("access_token").ifBlank { previous["access_token"].orEmpty() },
            "expired_at" to response("expired_at", "expiredAt").ifBlank { previous["expired_at"].orEmpty() },
            "musickeyCreateTime" to response("musickeyCreateTime", "musickey_create_time").ifBlank { previous["musickeyCreateTime"].orEmpty() },
            "keyExpiresIn" to response("keyExpiresIn", "key_expires_in").ifBlank { previous["keyExpiresIn"].orEmpty() },
        )
        optional.forEach { (name, value) -> value.takeIf(::safeCookieValue)?.let { pairs[name] = it } }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun readBoundedBody(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_QQ_RESPONSE_BYTES) { "QQ 音乐响应过大" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun webComm() = buildJsonObject {
        val gtk = hash33(cookieValue("qqmusic_key", "qm_keyst", "p_skey", "skey").orEmpty())
        put("ct", 24); put("cv", 4_747_474); put("platform", "yqq.json"); put("uin", accountId().ifBlank { "0" })
        put("g_tk", gtk); put("g_tk_new_20200303", gtk); put("format", "json"); put("inCharset", "utf-8"); put("outCharset", "utf-8"); put("notice", 0); put("need_new_code", 1)
    }

    private fun playbackComm(android: Boolean) = buildJsonObject {
        val id = accountId()
        val key = cookieValue("qqmusic_key", "qm_keyst").orEmpty()
        if (android) {
            put("ct", 11); put("cv", 20_030_508); put("v", 20_030_508)
            put("tmeAppID", "qqmusic"); put("chid", "10003505")
        } else {
            webComm().forEach { (name, value) -> put(name, value) }
            put("g_tk", hash33(key)); put("g_tk_new_20200303", hash33(key))
        }
        put("uin", id); put("qq", id); put("authst", key)
        put("tmeLoginType", cookieValue("tmeLoginType") ?: "1")
    }

    private fun findTracks(root: JsonElement): List<Track> = walkObjects(root).mapNotNull { value ->
        val mid = firstUsableQqSongMid(value.string("mid"), value.string("songmid"), value.string("song_mid"))
        val singers = value["singer"] as? JsonArray
        val album = value["album"] as? JsonObject
        if (!isUsableQqSongMid(mid) || singers == null || album == null) return@mapNotNull null
        val file = value["file"] as? JsonObject ?: JsonObject(emptyMap())
        val pay = value["pay"] as? JsonObject ?: JsonObject(emptyMap())
        val numericId = value.long("id")
        Track(
            id = mid, title = value.string("title").ifBlank { value.string("name") },
            artists = singers.mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) },
            album = album.string("title").ifBlank { album.string("name") },
            artworkUrl = qmusicAlbumArtworkUrl(album.string("mid")).ifBlank { qmusicSongArtworkUrl(mid) },
            playable = value.int("isonly") == 0 && pay.int("pay_play") == 0,
            qualities = parseQqQualityIds(value, file),
            numericId = numericId, mediaMid = file.string("media_mid"), songType = value.int("type"),
            requiresVip = value.int("isonly") != 0 || pay.int("pay_play") != 0
        )
    }.distinctBy { it.id }.toList()

    private fun findCollections(root: JsonElement, kind: String = "playlist"): List<MusicCollection> = walkObjects(root).mapNotNull { value ->
        val title = value.string("title").ifBlank { value.string("name") }.ifBlank { value.string("dirName") }
        val directoryId = value.string("dirId").ifBlank { value.string("dirid") }
        val id = when (kind) {
            "artist", "album" -> value.string("mid")
            else -> value.string("tid").ifBlank { value.string("id") }.ifBlank { directoryId }
        }
        val looksRight = when (kind) {
            "artist" -> value["singer"] == null && (value["uin"] != null || value["singerMid"] != null || value["mid"] != null)
            "album" -> value["time_public"] != null || value["albumMid"] != null
            else -> value["songNum"] != null || value["songnum"] != null || value["dirId"] != null || value["dirid"] != null
        }
        if (!looksRight || id.isBlank() || title.isBlank()) null else MusicCollection(
            id, title,
            normalizeHttpsUrl(value.string("picUrl").ifBlank { value.string("picurl") }.ifBlank { value.string("pic") }),
            value.int("songNum").takeIf { it > 0 } ?: value.int("songnum"),
            directoryId.ifBlank { id },
        )
    }.distinctBy { it.id }.toList()

    private fun walkObjects(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> { yield(element); element.values.forEach { yieldAll(walkObjects(it)) } }
            is JsonArray -> element.forEach { yieldAll(walkObjects(it)) }
            else -> Unit
        }
    }

    private fun streamPath(data: JsonElement): QqStreamPath? = walkObjects(data).firstNotNullOfOrNull { item ->
        listOf("purl", "wifiurl", "flowurl", "opi128kurl", "opi96kurl")
            .firstNotNullOfOrNull { key -> item.string(key).takeIf(String::isNotBlank)?.let { QqStreamPath(it, key) } }
    }

    private fun obj(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject { entries.forEach { (k, v) -> put(k, any(v)) } }
    private fun any(value: Any?): JsonElement = when (value) {
        null -> JsonNull; is JsonElement -> value; is String -> JsonPrimitive(value); is Number -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), any(v)) } }
        is Iterable<*> -> buildJsonArray { value.forEach { add(any(it)) } }
        else -> JsonPrimitive(value.toString())
    }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.long(key: String, default: Long = 0) = (this[key] as? JsonPrimitive)?.longOrNull ?: default
    private fun decodeText(value: String): String = decodeQqLyricText(value)
    private fun saved(key: String, factory: () -> String): String = prefs.getString(key, null) ?: factory().also { prefs.edit().putString(key, it).apply() }
    private fun hash33(value: String): Int = value.fold(5381) { hash, char -> hash + (hash shl 5) + char.code } and 0x7fffffff
    private fun cookieValues(value: String): Map<String, String> = buildMap {
        value.split(';').forEach { part ->
            val (name, content) = part.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            if (name.isNotBlank() && name !in this) put(name, content)
        }
    }
    private fun firstCookieValue(values: Map<String, String>, vararg names: String): String =
        names.firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }.orEmpty()
    private fun safeCookieValue(value: String): Boolean =
        value.isNotBlank() && value.length <= 8192 && value.none { it.code < 0x21 || it in setOf(';', ',', '\u007f') }
    private fun cookieValue(vararg names: String): String? =
        firstCookieValue(cookieValues(cookie().orEmpty()), *names).takeIf(String::isNotBlank)
    private fun accountId() = cookieValue("qqmusic_uin", "uin", "wxuin").orEmpty().trimStart('o')
    private fun requireLogin() { if (cookie().isNullOrBlank()) error("请先扫码登录") }
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_JSON_MEDIA = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        private const val WEB_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private const val PC_UA = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"
    }
}
