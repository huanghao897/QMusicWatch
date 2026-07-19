package com.ronan.qmusicwatch.network

import android.content.Context
import android.os.Build
import android.util.Base64
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.lyrics.QqQrcDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal fun normalizeHttpsUrl(value: String): String = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("http://", true) -> "https://${value.substringAfter("://")}"
    else -> value
}

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

internal fun normalizeLibraryData(value: LibraryData): LibraryData =
    value.copy(playlists = deduplicatePlaylists(value.playlists.filterNot(::isSystemLikedPlaylist)))

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

internal fun parseSearchTrack(item: JsonObject): Track? {
    fun text(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun number(name: String) = (item[name] as? JsonPrimitive)?.longOrNull ?: 0
    val file = item["file"] as? JsonObject ?: JsonObject(emptyMap())
    fun fileText(name: String) = (file[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun fileNumber(name: String) = (file[name] as? JsonPrimitive)?.longOrNull ?: 0
    val mid = text("songmid").ifBlank { text("mid") }; val title = text("songname").ifBlank { text("title") }
    if (mid.isBlank() || title.isBlank()) return null
    val pay = item["pay"] as? JsonObject ?: JsonObject(emptyMap())
    fun pay(name: String) = (pay[name] as? JsonPrimitive)?.intOrNull ?: 0
    val album = item["album"] as? JsonObject ?: JsonObject(emptyMap())
    val albumMid = text("albummid").ifBlank { (album["mid"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    val albumName = text("albumname").ifBlank { (album["title"] as? JsonPrimitive)?.contentOrNull.orEmpty() }.ifBlank { (album["name"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    return Track(mid, title, (item["singer"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }, albumName, albumMid.takeIf(String::isNotBlank)?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }.orEmpty(), true, parseQqQualityIds(item, file), numericId = number("songid").takeIf { it > 0 } ?: number("id"), mediaMid = fileText("media_mid"), songType = number("type").toInt(), requiresVip = text("isonly") == "1" || pay("payplay") != 0 || pay("pay_play") != 0)
}

internal fun nextSearchCursor(page: Int, rawItemCount: Int, pageSize: Int = 20): String? =
    (page + 1).toString().takeIf { rawItemCount >= pageSize }

internal fun playlistDirectoryNumber(value: String): Long =
    value.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("歌单目录标识无效")

/**
 * QQ Music HTTP client. Requests go from the watch straight to QQ Music; no QMusicWatch gateway is used.
 * The protocol shape is based on the Apache-2.0 fovepig/QQmusic-API project.
 */
class ApiClient(context: Context, private val cookie: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(18, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("qq_direct_api", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val guid = saved("guid") { (random.nextLong().ushr(1) % 10_000_000_000L).toString() }

    suspend fun home(): HomeData = withContext(Dispatchers.IO) {
        val personalized = if (cookie().isNullOrBlank()) emptyList() else runCatching {
            api(
                "music.radioProxy.MbTrackRadioSvr", "get_radio_track",
                obj("id" to 99, "num" to 20, "from" to 0, "scene" to 0, "song_ids" to emptyList<Long>()),
            ).let(::findTracks)
        }.onFailure { AppLog.write("HOME", "personalized ${it.javaClass.simpleName}:${it.message.orEmpty()}") }.getOrDefault(emptyList())
        val daily = if (personalized.size >= 20) personalized else {
            val fallback = runCatching { api("newsong.NewSongServer", "get_new_song_info", obj("type" to 5)).let(::findTracks) }
                .onFailure { AppLog.write("HOME", "fallback ${it.javaClass.simpleName}:${it.message.orEmpty()}") }
                .getOrDefault(emptyList())
            (personalized + fallback).distinctBy(Track::id)
        }
        HomeData(daily, emptyList())
    }

    suspend fun searchTracks(query: String, cursor: String? = null): PagedTracks = withContext(Dispatchers.IO) {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val items = webSearch(query, page, 0)["song"]?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        val tracks = items.mapNotNull { it as? JsonObject }.mapNotNull(::parseSearchTrack)
        PagedTracks(tracks, nextSearchCursor(page, items.size))
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
        val data = webApi(
            "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
            obj("songMid" to id, "crypt" to 0, "qrc" to 0, "qrc_t" to 0, "trans" to 1, "trans_t" to 0, "roma" to 0, "roma_t" to 0, "type" to 1, "ct" to 24, "cv" to 4_747_474)
        )
        val qrc = runCatching {
            webApi(
                "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
                obj("songMid" to id, "crypt" to 1, "qrc" to 1, "qrc_t" to 0, "trans" to 0, "trans_t" to 0, "roma" to 0, "roma_t" to 0, "type" to 1, "ct" to 24, "cv" to 4_747_474),
            ).string("lyric").takeIf { it.isNotBlank() }?.let(QqQrcDecoder::decode)
        }.onFailure { AppLog.write("LYRICS", "qrc ${it.javaClass.simpleName}:${it.message.orEmpty()}") }.getOrNull()
        LyricsData(
            decodeText(data.string("lyric")),
            decodeText(data.string("trans")).ifBlank { null },
            qrc,
        )
    }

    suspend fun stream(track: Track, quality: String): StreamData = withContext(Dispatchers.IO) {
        requireLogin()
        val preferred = normalizeQualityId(quality)
        AppLog.write("STREAM", "request track=${track.id} preferred=$preferred vip=${track.requiresVip}")
        val complete = if (track.mediaMid.isBlank()) trackDetail(track.id) else track
        val qualities = qualityFallbackOrder(preferred)
        var firstFailure: Throwable? = null
        var receivedResponse = false
        var bestFallback: StreamData? = null
        qualities.forEach { requested ->
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
                    if (error.message.orEmpty().contains("登录凭据已失效")) throw error
                    if (firstFailure == null) firstFailure = error
                    AppLog.write("STREAM", "attempt=$module failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    return@attempt
                }
                receivedResponse = true
                val path = streamPath(data) ?: return@attempt
                if (path.value.isNotBlank()) {
                    val base = walkObjects(data).firstNotNullOfOrNull { item ->
                        (item["sip"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                    }.orEmpty().let(::normalizeHttpsUrl)
                    val url = if (path.value.startsWith("http")) path.value else "${base.ifBlank { "https://isure.stream.qqmusic.qq.com/" }}${if (base.endsWith('/')) "" else "/"}${path.value}"
                    val actual = inferQqStreamQuality(path.value, path.sourceKey)
                    val stream = StreamData(url, actual, System.currentTimeMillis() + data.long("expiration", 3600) * 1000)
                    AppLog.write("STREAM", "issued requested=$requested actual=$actual via=$module")
                    bestFallback = higherQualityStream(bestFallback, stream)
                    if (actual == requested) return@withContext bestFallback!!
                }
            }
        }
        bestFallback?.let { return@withContext it }
        if (!receivedResponse) firstFailure?.let { throw it }
        AppLog.write("STREAM", "no-url track=${track.id} vip=${complete.requiresVip}")
        error(if (complete.requiresVip) "这首歌需要 VIP 或购买" else "QQ 音乐未提供播放地址，可能存在版权、地区或账号权益限制")
    }

    suspend fun library(): LibraryData = withContext(Dispatchers.IO) {
        requireLogin()
        val playlists = api("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", obj("uin" to accountId()))
        val collectedPlaylists = mutableListOf<MusicCollection>()
        val collectedDirectories = mutableSetOf<String>()
        for (page in 0 until 20) {
            val result = runCatching {
                api(
                    "music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                    obj("uin" to accountId(), "offset" to page * 100, "size" to 100),
                ).let(::parseFavoritePlaylists)
            }.onFailure { AppLog.write("LIBRARY", "favorite playlists ${it.javaClass.simpleName}:${it.message.orEmpty()}") }
            val batch = result.getOrNull() ?: break
            val previousSize = collectedPlaylists.size
            collectedPlaylists += batch.filter { collectedDirectories.add(it.directoryId) }
            if (batch.size < 100 || collectedPlaylists.size == previousSize) break
        }
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
        val accountPlaylists = parseAccountPlaylists(playlists)
        normalizeLibraryData(
            LibraryData(likedTracks, mergeLibraryPlaylists(accountPlaylists, collectedPlaylists)),
        )
    }

    suspend fun profile(): UserProfile = withContext(Dispatchers.IO) {
        requireLogin()
        val id = accountId()
        val roots = mutableListOf<JsonObject>()
        listOf("GetLoginUserInfo", "GetUserInfo").forEach { method ->
            runCatching { api("music.UserInfo.userInfoServer", method, obj("user_uin" to id, "login_uin" to id, "uin" to id)) }.getOrNull()?.let { data -> AppLog.write("PROFILE", "$method keys=${data.keys.joinToString(",").take(300)}"); roots += data }
        }
        runCatching { api("VipLogin.VipLoginInter", "vip_login_base", obj()) }.getOrNull()?.let { data ->
            AppLog.write("PROFILE", "vip_login_base keys=${data.keys.joinToString(",").take(300)}")
            roots += buildJsonObject { put("vip_response", data) }
        }
        runCatching {
            val gtk = hash33(cookieValue("qqmusic_key", "qm_keyst", "p_skey", "skey").orEmpty())
            val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&loginUin=$id&hostUin=0&userid=$id&g_tk=$gtk&cid=205360838&reqfrom=1"
            val builder = Request.Builder().url(url).header("Referer", "https://y.qq.com/").header("User-Agent", WEB_UA)
            cookie()?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            http.newCall(builder.build()).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}"); json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
        }.getOrNull()?.let { roots += it }
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
            .header("Range", "bytes=0-0").header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com").header("User-Agent", WEB_UA).build()
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
        api(
            "music.musicasset.SongFavWrite", if (liked) "CgiAddSongFav" else "CgiDelSongFav",
            obj("v_songId" to listOf(complete.numericId))
        )
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
                else -> api("music.srfDissInfo.DissInfo", "CgiGetDiss", obj("disstid" to (collection.id.toLongOrNull() ?: 0), "dirid" to (collection.directoryId.toLongOrNull() ?: 0), "tag" to true, "song_begin" to begin, "song_num" to 100, "userinfo" to true, "orderlist" to true))
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
        val data = api("music.musicasset.PlaylistBaseWrite", "AddPlaylist", obj("dirName" to title))
        val item = walkObjects(data).firstOrNull { it["dirId"] != null || it["tid"] != null } ?: JsonObject(emptyMap())
        val dirId = item.string("dirId")
        playlistDirectoryNumber(dirId)
        MusicCollection(item.string("tid").ifBlank { dirId }, title, directoryId = dirId)
    }

    suspend fun renamePlaylist(id: String, title: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        api("music.musicasset.PlaylistBaseWrite", "ModifyPlaylist", obj("dirId" to playlistDirectoryNumber(id), "dirName" to title))
        Ack(true)
    }

    suspend fun deletePlaylist(id: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        api("music.musicasset.PlaylistBaseWrite", "DelPlaylist", obj("dirId" to playlistDirectoryNumber(id)))
        Ack(true)
    }

    suspend fun changePlaylistTrack(id: String, track: Track, add: Boolean): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        val complete = if (track.numericId > 0) track else trackDetail(track.id)
        if (complete.numericId <= 0) error("QQ 音乐未返回歌曲数字标识，无法修改歌单")
        api(
            "music.musicasset.PlaylistDetailWrite", if (add) "AddSonglist" else "DelSonglist",
            obj("dirId" to playlistDirectoryNumber(id), "tid" to 0, "bFmtUtf8" to true, "v_songInfo" to listOf(mapOf("songId" to complete.numericId, "songType" to complete.songType)))
        )
        Ack(true)
    }

    private fun smartSearch(query: String): JsonObject {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&key=${java.net.URLEncoder.encode(query, "UTF-8")}" 
        val request = Request.Builder().url(url).header("Referer", "https://y.qq.com/").header("User-Agent", WEB_UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("QQ 音乐搜索接口错误 ${response.code}")
            return json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["data"]?.jsonObject
                ?: error("QQ 音乐搜索响应无效")
        }
    }

    private fun webSearch(query: String, page: Int, type: Int): JsonObject {
        val url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?format=json&p=$page&n=20&t=$type&w=${java.net.URLEncoder.encode(query, "UTF-8")}" 
        val request = Request.Builder().url(url).header("Referer", "https://y.qq.com/").header("User-Agent", WEB_UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("QQ 音乐搜索接口错误 ${response.code}")
            return json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["data"]?.jsonObject ?: error("QQ 音乐搜索响应无效")
        }
    }

    private fun searchCollection(type: String, item: JsonObject): MusicCollection? {
        val id = when (type) { "artist" -> item.string("singerMID").ifBlank { item.string("mid") }; "album" -> item.string("albumMID").ifBlank { item.string("mid") }; else -> item.string("dissid").ifBlank { item.string("id") }.ifBlank { item.string("mid") } }
        val title = when (type) { "artist" -> item.string("singerName").ifBlank { item.string("name") }; "album" -> item.string("albumName").ifBlank { item.string("name") }; else -> item.string("dissname").ifBlank { item.string("name") } }
        if (id.isBlank() || title.isBlank()) return null
        val count = listOf("songNum", "song_count", "songnum", "total").firstNotNullOfOrNull { key -> item[key]?.jsonPrimitive?.intOrNull } ?: -1
        return MusicCollection(id, title, normalizeHttpsUrl(item.string("singerPic").ifBlank { item.string("imgurl") }.ifBlank { item.string("pic") }), count)
    }

    private suspend fun trackDetail(mid: String): Track {
        val data = webApi("music.pf_song_detail_svr", "get_song_detail_yqq", obj("song_mid" to mid))
        return findTracks(data).firstOrNull() ?: error("无法读取歌曲详情")
    }

    private suspend fun api(module: String, method: String, param: JsonObject): JsonObject {
        return post(webComm(), module, method, param)
    }

    private suspend fun webApi(module: String, method: String, param: JsonObject): JsonObject =
        post(webComm(), module, method, param)

    private fun post(
        comm: JsonObject, module: String, method: String, param: JsonObject,
        requestCookie: String? = cookie(), tolerateBusinessError: Boolean = false,
    ): JsonObject {
        val payload = buildJsonObject {
            put("comm", comm)
            putJsonObject("req_0") { put("module", module); put("method", method); put("param", param) }
        }
        val builder = Request.Builder().url(MUSIC_API).post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("User-Agent", if (comm.int("ct") == 11) "QQMusic 14090008(android ${Build.VERSION.RELEASE})" else WEB_UA)
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
        requestCookie?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
        val started = System.currentTimeMillis()
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppLog.write("API", "$module/$method http=${response.code} ms=${System.currentTimeMillis() - started}")
                error("QQ 音乐接口错误 ${response.code}")
            }
            val root = json.parseToJsonElement(text).jsonObject
            val req = root["req_0"]?.jsonObject ?: error("QQ 音乐响应格式已变化")
            val code = req.int("code")
            AppLog.write("API", "$module/$method http=${response.code} code=$code ms=${System.currentTimeMillis() - started}")
            if (code in setOf(1000, 104400, 104401)) error("登录凭据已失效，请重新登录")
            if (code != 0 && !tolerateBusinessError) error(req.string("msg").ifBlank { "QQ 音乐接口拒绝请求 ($code)" })
            return req["data"]?.jsonObject ?: JsonObject(emptyMap())
        }
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
        val mid = value.string("mid")
        val singers = value["singer"] as? JsonArray
        val album = value["album"] as? JsonObject
        if (mid.isBlank() || singers == null || album == null) return@mapNotNull null
        val file = value["file"] as? JsonObject ?: JsonObject(emptyMap())
        val pay = value["pay"] as? JsonObject ?: JsonObject(emptyMap())
        val numericId = value.long("id")
        Track(
            id = mid, title = value.string("title").ifBlank { value.string("name") },
            artists = singers.mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) },
            album = album.string("title").ifBlank { album.string("name") },
            artworkUrl = album.string("mid").takeIf(String::isNotBlank)?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }.orEmpty(),
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
    private fun decodeText(value: String): String = if (value.isBlank()) "" else runCatching { Base64.decode(value, Base64.DEFAULT).decodeToString() }.getOrDefault(value)
    private fun saved(key: String, factory: () -> String): String = prefs.getString(key, null) ?: factory().also { prefs.edit().putString(key, it).apply() }
    private fun hash33(value: String): Int = value.fold(5381) { hash, char -> hash + (hash shl 5) + char.code } and 0x7fffffff
    private fun cookieValue(vararg names: String): String? = cookie()?.split(';')?.map(String::trim)?.firstNotNullOfOrNull { part -> names.firstOrNull { part.startsWith("$it=") }?.let { part.substringAfter('=') } }
    private fun accountId() = cookieValue("qqmusic_uin", "uin", "wxuin").orEmpty().trimStart('o')
    private fun requireLogin() { if (cookie().isNullOrBlank()) error("请先扫码登录") }
    companion object {
        private const val MUSIC_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val WEB_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
