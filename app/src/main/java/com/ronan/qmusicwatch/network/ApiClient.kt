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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun normalizeHttpsUrl(value: String): String = trustedQMusicMediaUrl(value)

private val invalidQqSongMids = setOf("null", "undefined", "nil")

internal fun isUsableQqSongMid(value: String): Boolean {
    val normalized = value.trim()
    return normalized.isNotEmpty() &&
        normalized.any { it != '0' } &&
        normalized.lowercase() !in invalidQqSongMids
}

private fun firstUsableQqSongMid(vararg values: String): String =
    values.asSequence().map(String::trim).firstOrNull(::isUsableQqSongMid).orEmpty()

private class QqBusinessException(val businessCode: Int, message: String) : IllegalStateException(message)
private class QqCredentialExpiredException(message: String) : IllegalStateException(message)
internal class QMusicGatewayException(
    val statusCode: Int,
    val errorCode: String,
    message: String,
) : IllegalStateException(message)

private const val LOGIN_USER_INFO_MODULE = "music.UserInfo.userInfoServer"
private const val LOGIN_CREDENTIAL_PROBE_METHOD = "GetLoginUserInfo"

internal fun shouldRefreshCredential(module: String, method: String, code: Int): Boolean =
    code in setOf(104400, 104401) ||
        (code == 1000 && module == LOGIN_USER_INFO_MODULE && method == LOGIN_CREDENTIAL_PROBE_METHOD)

private fun isLoginCredentialProbe(module: String, method: String): Boolean =
    module == LOGIN_USER_INFO_MODULE && method == LOGIN_CREDENTIAL_PROBE_METHOD

internal fun requiresNewQrLogin(error: Throwable): Boolean =
    error is QMusicGatewayException && error.errorCode == "RELOGIN_REQUIRED"

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
    require(mediaMid.isNotBlank()) { "æ­Œæ›²ç¼ºå°‘åª’ä½“æ ‡è¯†" }
    val (prefix, extension) = when (normalizeQualityId(quality)) {
        QUALITY_SQ -> "F000" to "flac"
        QUALITY_HQ -> "M800" to "mp3"
        QUALITY_HI_RES -> throw IllegalArgumentException("Hi-Res èµ„æºæ ¼å¼å°šæœªå®Œæˆå…¼å®¹éªŒè¯")
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
    "æˆ‘å–œæ¬¢", "æˆ‘å–œæ¬¢çš„éŸ³ä¹", "æˆ‘å–œæ¬¢çš„æ­Œæ›²",
    "æˆ‘å–œæ­¡", "æˆ‘å–œæ­¡çš„éŸ³æ¨‚", "æˆ‘å–œæ­¡çš„æ­Œæ›²",
)

internal fun isSystemLikedPlaylist(value: MusicCollection): Boolean {
    val directoryId = value.directoryId.trim().toLongOrNull()
    if (directoryId == 201L) return true

    val normalizedTitle = value.title.filterNot(Char::isWhitespace)
    return value.owned == false && directoryId == 0L && normalizedTitle in systemLikedPlaylistTitles
}

private fun stableTrackArtwork(track: Track): Track {
    val current = trustedQMusicMediaUrl(track.artworkUrl)
    val stable = when {
        current.isBlank() -> qmusicSongArtworkUrl(track.id)
        "/api/qmusic-watch/gateway/media/" in current -> qmusicSongArtworkUrl(track.id)
        else -> current
    }
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
    rank >= 3 || path.contains("svip", true) || label.contains("è¶…çº§") || label.contains("SVIP", true) -> "svip"
    rank == 2 || path.contains("green", true) || path.contains("luxury", true) || label.contains("ç»¿é’»") -> "green_diamond"
    rank == 1 || label.isNotBlank() -> "vip"
    else -> ""
}

private fun rankForMembership(path: String, label: String, typeCode: Int?, superFlag: Boolean): Int = when {
    superFlag || path.contains("svip", true) || label.contains("è¶…çº§") || label.contains("SVIP", true) -> 3
    typeCode != null && typeCode >= 11 -> 3
    path.contains("green", true) || path.contains("luxury", true) || label.contains("ç»¿é’»") -> 2
    typeCode != null && typeCode >= 2 -> 2
    label.contains("ä¼šå‘˜") || label.contains("vip", true) || typeCode == 1 -> 1
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
            pathAndLabel.contains("å¬ä¹¦", true) || pathAndLabel.contains("book", true) -> "å¬ä¹¦ä¼šå‘˜"
            rank >= 3 -> "è¶…çº§ä¼šå‘˜ï¼ˆSVIPï¼‰"
            pathAndLabel.contains("ç»¿é’»", true) || pathAndLabel.contains("green", true) || pathAndLabel.contains("luxury", true) || rank == 2 -> "è±ªåç»¿é’»"
       ×mºÒÚ$z{-®éÜj×J.Yîi*ŞiKâ"Ğ¢fÂFFÒvV$’‚&×W6–2çe÷6öæuöFWF–Å÷7g""Â&vWE÷6öæuöFWF–Å÷—"Âö&¢‚'6öæuöÖ–B"FòÖ–B’¢&WGW&âf–æEG&6·2†FF’æf—'7D÷$çVÆÂ‚’ó¢W'&÷"‚.izk9^Šû¾XùnjØÎi».Šúnh8R"¢Ğ ¢&—fFR7W7VæBgVâ’†ÖöGVÆS¢7G&–ærÂÖWF†öC¢7G&–ærÂ&Ó¢§6öäö&¦V7B“¢§6öäö&¦V7B°¢&WGW&â÷7B‡vV$6öÖÒ‚’ÂÖöGVÆRÂÖWF†öBÂ&Ò¢Ğ ¢&—fFR7W7VæBgVâvV$’€¢ÖöGVÆS¢7G&–ærÀ¢ÖWF†öC¢7G&–ærÀ¢&Ó¢§6öäö&¦V7BÀ¢6ÆÅF–ÖV÷WD×3¢ÆöæsòÒçVÆÂÀ¢“¢§6öäö&¦V7BÒ÷7B‡vV$6öÖÒ‚’ÂÖöGVÆRÂÖWF†öBÂ&ÒÂ6ÆÅF–ÖV÷WD×2Ò6ÆÅF–ÖV÷WD×2 ¢&—fFRgVâ&WV—&Uw&—FT66WFVB†FF¢§6öäö&¦V7B’°¢fÂ6öFRÒw&—FT'W6–æW746öFR†FF’ó¢&WGW&à¢–b†6öFRÒ’F‡&÷r'W6–æW74W†6WF–öâ€¢6öFRÀ¢%™û>K™k*iÈKùŞZÙ‹ùjÊKúîiK’‚F6öFR’"À¢¢Ğ ¢&—fFRgVâ÷7B€¢6öÖÓ¢§6öäö&¦V7BÂÖöGVÆS¢7G&–ærÂÖWF†öC¢7G&–ærÂ&Ó¢§6öäö&¦V7BÀ¢&WVW7D6öö¶–S¢7G&–æsòÒ6öö¶–R‚’ÂFöÆW&FT'W6–æW74W'&÷#¢&ööÆVâÒfÇ6RÀ¢ÆÆ÷t7&VFVçF–Å&Vg&W6ƒ¢&ööÆVâÒG'VRÀ¢6ÆÅF–ÖV÷WD×3¢ÆöæsòÒçVÆÂÀ¢“¢§6öäö&¦V7B°¢fÂ–ÆöBÒ'V–ÆD§6öäö&¦V7B°¢&WVW7D6öö¶–SòçF¶T–b…7G&–æs£¦—4æ÷D&Ææ²“òæÆWB²WB‚&6öö¶–R"Â—B’Ğ¢WB‚&6öÖÒ"Â6öÖÒ¢WB‚&ÖöGVÆR"ÂÖöGVÆR¢WB‚&ÖWF†öB"ÂÖWF†öB¢WB‚'&Ò"Â&Ò¢Ğ¢fÂ7F'FVBÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚¢fÂ&W7VÇBÒG'’°¢vFWv•&WVW7B‚&×W6–7R"Â–ÆöBÂ6ÆÅF–ÖV÷WD×2¢Ò6F6‚†6æ6VÆÆVC¢6æ6VÆÆF–öäW†6WF–öâ’°¢F‡&÷r6æ6VÆÆV@¢Ò6F6‚†W'&÷#¢F‡&÷v&ÆR’°¢Æörçw&—FR‚$’"Â"FÖöGVÆRòFÖWF†öBvFWv•öW'&÷#ÒG¶W'&÷"æ¦f6Æ72ç6–×ÆTæÖWÒ×3ÒGµ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚’Ò7F'FVGÒ"¢F‡&÷rW'&÷ ¢Ğ¢fÂ6öFRÒ&W7VÇBæ–çB‚&6öFR"¢Æörçw&—FR‚$’"Â"FÖöGVÆRòFÖWF†öB6öFSÒF6öFR×3ÒGµ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚’Ò7F'FVGÒ"¢–b‡6†÷VÆE&Vg&W6„7&VFVçF–Â†ÖöGVÆRÂÖWF†öBÂ6öFR’’°¢fÂ7FÆT6öö¶–RÒ&WVW7D6öö¶–Ræ÷$V×G’‚¢fÂ&Vg&W6†VBÒÆÆ÷t7&VFVçF–Å&Vg&W6‚bb7FÆT6öö¶–Ræ—4æ÷D&Ææ²‚’bb'Vä6F6†–ær°¢&Vg&W6„7&VFVçF–Ä&Æö6¶–ær‡7FÆT6öö¶–RÂ×W6–46öö¶–Rç&÷f–FW"‡7FÆT6öö¶–RÂ'"’¢Òæöäf–ÇW&R²W'&÷"Óà¢Æörçw&—FR‚$UD‚"Â&7&VFVçF–Â&Vg&W6‚f–ÆVBG¶W'&÷"æ¦f6Æ72ç6–×ÆTæÖWÒ"¢ÒævWD÷$FVfVÇB†fÇ6R¢–b‡&Vg&W6†VB’°¢&WGW&â÷7B€¢6öÖÒÒ&WVW7D6öÖÔgFW$7&VFVçF–Å&Vg&W6‚†ÖöGVÆR’À¢ÖöGVÆRÒÖöGVÆRÀ¢ÖWF†öBÒÖWF†öBÀ¢&ÒÒ&ÒÀ¢&WVW7D6öö¶–RÒ6öö¶–R‚’À¢FöÆW&FT'W6–æW74W'&÷"ÒFöÆW&FT'W6–æW74W'&÷"À¢ÆÆ÷t7&VFVçF–Å&Vg&W6‚ÒfÇ6RÀ¢6ÆÅF–ÖV÷WD×2Ò6ÆÅF–ÖV÷WD×2À¢¢Ğ¢F‡&÷r7&VFVçF–ÄW‡—&VDW†6WF–öâ‚.y›¾[Ù^x«nh[{.ZKiXûÈÎŠû~˜xŞikhš¾zy›¾[Ù^KˆjÊ"¢Ğ¢–b†6öFRÓÒbb—4Æöv–ä7&VFVçF–Å&ö&R†ÖöGVÆRÂÖWF†öB’’°¢Ö&´7&VFVçF–ÅfW&–f–VB‡&WVW7D6öö¶–Ræ÷$V×G’‚’¢Ğ¢–b†6öFRÒbbFöÆW&FT'W6–æW74W'&÷"’F‡&÷r'W6–æW74W†6WF–öâ€¢6öFRÀ¢&W7VÇBç7G&–ær‚&ÖW76vR"’æ–d&Ææ²²%™û>K™hê^Xú>h¹.{¹ŞŠû~k"‚F6öFR’"ÒÀ¢¢&WGW&â&W7VÇE²&FF%Óòæ§6öäö&¦V7Bó¢§6öäö&¦V7B†V×G”Ö‚’¢Ğ ¢&—fFRgVâvFWv”ÆVv7’€¢÷W&F–öã¢7G&–ærÀ¢VW'“¢7G&–ærÒ""À¢vS¢–çBÒÀ¢G—S¢–çBÒÀ¢“¢§6öäö&¦V7B°¢fÂ–ÆöBÒ'V–ÆD§6öäö&¦V7B°¢WB‚&÷W&F–öâ"Â÷W&F–öâ¢6öö¶–R‚“òçF¶T–b…7G&–æs£¦—4æ÷D&Ææ²“òæÆWB²WB‚&6öö¶–R"Â—B’Ğ¢–b†÷W&F–öâ–â6WDöb‚'6V&6‚"Â'6Ö'E6V&6‚"’’WB‚'VW'’"ÂVW'’¢–b†÷W&F–öâÓÒ'6V&6‚"’°¢WB‚'vR"ÂvR¢WB‚'G—R"ÂG—R¢Ğ¢Ğ¢&WGW&âvFWv•&WVW7B‚&ÆVv7’"Â–ÆöB¢Ğ ¢&—fFRgVâvFWv•&WVW7B€¢&÷WFS¢7G&–ærÀ¢–ÆöC¢§6öäö&¦V7BÀ¢6ÆÅF–ÖV÷WD×3¢ÆöæsòÒçVÆÂÀ¢“¢§6öäö&¦V7B°¢&WGW&âvFWv•÷7B‚&’÷×W6–2×vF6‚övFWv’òG&÷WFR"Â–ÆöBÂ6ÆÅF–ÖV÷WD×2¢Ğ ¢&—fFRgVâvFWv•÷7B€¢Fƒ¢7G&–ærÀ¢–ÆöC¢§6öäö&¦V7BÀ¢6ÆÅF–ÖV÷WD×3¢ÆöæsòÒçVÆÂÀ¢“¢§6öäö&¦V7B°¢fÂ&WVW7BÒ&WVW7Bä'V–ÆFW"‚¢çW&Â‡×W6–56W'fW$VæGö–çB‡F‚’¢ç÷7B‡–ÆöBçFõ7G&–ær‚’çFõ&WVW7D&öG’„¥4ôåôÔTD”’¢æ†VFW"‚$66WB"Â&Æ–6F–öâö§6öâ"¢æ†VFW"‚%W6W"ÔvVçB"ÂtT%õT¢æ'V–ÆB‚¢fÂ6ÆÂÒ‡GGææWt6ÆÂ‡&WVW7B¢6ÆÅF–ÖV÷WD×3òçF¶T–b²—BâÓòæÆWB°¢6ÆÂçF–ÖV÷WB‚’çF–ÖV÷WB†—BÂF–ÖUVæ—BäÔ”ÄÄ•4T4ôäE2¢Ğ¢6ÆÂæW†V7WFR‚’çW6R²&W7öç6RÓà¢fÂFW‡BÒ&W7öç6Ræ&öG“òæ'—FU7G&VÒ‚“òçW6Rƒ£§&VDvFWv”&öG’’æ÷$V×G’‚¢fÂ&ö÷BÒ'Vä6F6†–ær²§6öâç'6UFô§6öäVÆVÖVçB‡FW‡B’æ§6öäö&¦V7BĞ¢ævWD÷$VÇ6R²W'&÷"‚.™û>K™iÈŞXªYšY8Ş[©NjÎ[ÈşiziX‚"’Ğ¢–b‚&W7öç6Ræ—57V66W76gVÂÇÂ&ö÷E²&ö²%Óòæ§6öå&–Ö—F—fSòæ&ööÆVä÷$çVÆÂÒG'VR’°¢fÂvFWv”W'&÷"Ò&ö÷E²&W'&÷"%Ò3ò§6öäö&¦V7@¢fÂÖW76vRÒvFWv”W'&÷#òç7G&–ær‚&ÖW76vR"¢æ÷$V×G’‚’çF¶Rƒc’æ–d&Ææ²².™û>K™iÈŞXªYšY8Ş[©BG·&W7öç6Ræ6öFWÒ"Ğ¢F‡&÷r×W6–4vFWv”W†6WF–öâ€¢7FGW46öFRÒ&W7öç6Ræ6öFRÀ¢W'&÷$6öFRÒvFWv”W'&÷#òç7G&–ær‚&6öFR"’æ÷$V×G’‚’À¢ÖW76vRÒÖW76vRÀ¢¢Ğ¢&WGW&â&ö÷E²&FF%Óòæ§6öäö&¦V7Bó¢W'&÷"‚.™û>K™iÈŞXªYšY8Ş[©N{Ë®[	i[hÚâ"¢Ğ¢Ğ ¢&—fFRgVâ&WVW7D6öÖÔgFW$7&VFVçF–Å&Vg&W6‚†ÖöGVÆS¢7G&–ær“¢§6öäö&¦V7BÒv†Vâ†ÖöGVÆR’°¢'f¶W’ävWEf¶W•6W'fW""ÓâÆ–&6´6öÖÒ†æG&ö–BÒfÇ6R¢&×W6–2çf¶W’ävWEf¶W’"ÓâÆ–&6´6öÖÒ†æG&ö–BÒG'VR¢VÇ6RÓâvV$6öÖÒ‚¢Ğ ¢&—fFRgVâ&ö&UÆ–&6´7&VFVçF–Â‚“¢&ööÆVâ°¢fÂ7FÆT6öö¶–RÒ6öö¶–R‚’æ÷$V×G’‚¢–b‡7FÆT6öö¶–Ræ—4&Ææ²‚’’W'&÷"‚.Šû~XXy›¾[ÙR"¢fÂæ÷rÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚¢–b‡7FÆT6öö¶–RÓÒfW&–f–VD7&VFVçF–Ä6öö¶–Rbbæ÷rÂ7&VFVçF–ÅfW&–f–VEVçF–Â’°¢Æörçw&—FR‚$UD‚"Â'Æ–&6²7&VFVçF–Â&ö&R66†RÖ†—B"¢&WGW&âfÇ6P¢Ğ¢÷7B€¢6öÖÒÒvV$6öÖÒ‚’À¢ÖöGVÆRÒÄôt”åõU4U%ô”ädõôÔôETÄRÀ¢ÖWF†öBÒÄôt”åô5$TDTåD”Åõ$ô$UôÔUD„ôBÀ¢&ÒÒö&¢‚’À¢&WVW7D6öö¶–RÒ7FÆT6öö¶–RÀ¢¢fÂ7W'&VçD6öö¶–RÒ6öö¶–R‚’æ÷$V×G’‚¢Ö&´7&VFVçF–ÅfW&–f–VB†7W'&VçD6öö¶–R¢&WGW&â7W'&VçD6öö¶–Ræ—4æ÷D&Ææ²‚’bb7W'&VçD6öö¶–RÒ7FÆT6öö¶–P¢Ğ ¢&—fFRgVâÖ&´7&VFVçF–ÅfW&–f–VB‡fÇVS¢7G&–ær’°¢–b‡fÇVRæ—4&Ææ²‚’’&WGW&à¢fW&–f–VD7&VFVçF–Ä6öö¶–RÒfÇVP¢7&VFVçF–ÅfW&–f–VEVçF–ÂÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚’²R¢cóÀ¢Ğ ¢&—fFRgVâ&Vg&W6„7&VFVçF–Ä&Æö6¶–ær‡7FÆT6öö¶–S¢7G&–ærÂ&÷f–FW#¢7G&–ær“¢&ööÆVâĞ¢7–æ6‡&öæ—¦VB†7&VFVçF–Å&Vg&W6„Æö6²’°¢fÂ7W'&VçD6öö¶–RÒ6öö¶–R‚’æ÷$V×G’‚¢–b†7W'&VçD6öö¶–Ræ—4æ÷D&Ææ²‚’bb7W'&VçD6öö¶–RÒ7FÆT6öö¶–R’&WGW&ä7–æ6‡&öæ—¦VBG'VP¢fÂæ÷rÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚¢–b‡7FÆT6öö¶–RÓÒ&V6VçFÇ•&Vg&W6†VD6öö¶–Rbbæ÷rÒ&V6VçFÇ•&Vg&W6†VDBÂcóÂ’°¢Æörçw&—FR‚$UD‚"Â&7&VFVçF–Â&Vg&W6‚7W&W76VBgFW"&V6VçB&÷FF–öâ"¢&WGW&ä7–æ6‡&öæ—¦VBfÇ6P¢Ğ¢fÂ–ÆöBÒ'V–ÆD§6öäö&¦V7B°¢WB‚'&÷f–FW""Â&÷f–FW"¢WB‚&6öö¶–R"Â7FÆT6öö¶–R¢Ğ¢fÂFFÒvFWv•÷7B‚&’÷×W6–2×vF6‚öWF‚÷&Vg&W6‚"Â–ÆöB¢fÂ&Vg&W6†VBÒfÆ–FFU&Vg&W6†VD6öö¶–R‡7FÆT6öö¶–RÂFFç7G&–ær‚&6öö¶–R"’¢WFFT6öö¶–R‡&Vg&W6†VB¢7G&VÔ66†Ræ6ÆV"‚¢fW&–f–VD7&VFVçF–Ä6öö¶–RÒ" ¢7&VFVçF–ÅfW&–f–VEVçF–ÂÒÀ¢&V6VçFÇ•&Vg&W6†VD6öö¶–RÒ&Vg&W6†V@¢&V6VçFÇ•&Vg&W6†VDBÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚¢Æörçw&—FR‚$UD‚"Â&7&VFVçF–Â&Vg&W6†VB&÷f–FW#ÒG&÷f–FW""¢G'VP¢Ğ ¢&—fFRgVâ&VDvFWv”&öG’†–çWC¢¦fæ–òä–çWE7G&VÒ“¢7G&–ær°¢fÂ÷WGWBÒ'—FT'&”÷WGWE7G&VÒ‚¢fÂ'VffW"Ò'—FT'&’ƒ‚¢#B¢f"F÷FÂÒ ¢v†–ÆR‡G'VR’°¢fÂ&VBÒ–çWBç&VB†'VffW"¢–b‡&VBÂ’'&V°¢F÷FÂ³Ò&V@¢&WV—&R‡F÷FÂÃÒB¢#B¢#B’².™û>K™iÈŞXªYšY8Ş[©N‹ø~ZJr"Ğ¢÷WGWBçw&—FR†'VffW"ÂÂ&VB¢Ğ¢&WGW&â÷WGWBçFõ7G&–ær„6†'6WG2åUDeó‚ææÖR‚’¢Ğ ¢&—fFRgVâvV$6öÖÒ‚’Ò'V–ÆD§6öäö&¦V7B°¢fÂwF²Ò†6ƒ32†6öö¶–UfÇVR‚'×W6–5ö¶W’"Â'Õö¶W—7B"Â'÷6¶W’"Â'6¶W’"’æ÷$V×G’‚’¢WB‚&7B"Â#B“²WB‚&7b"ÂEósCuóCsB“²WB‚'ÆFf÷&Ò"Â'—æ§6öâ"“²WB‚'V–â"Â66÷VçD–B‚’æ–d&Ææ²²#"Ò¢WB‚&u÷F²"ÂwF²“²WB‚&u÷FµöæWuó##32"ÂwF²“²WB‚&f÷&ÖB"Â&§6öâ"“²WB‚&–ä6†'6WB"Â'WFbÓ‚"“²WB‚&÷WD6†'6WB"Â'WFbÓ‚"“²WB‚&æ÷F–6R"Â“²WB‚&æVVEöæWuö6öFR"Â¢Ğ ¢&—fFRgVâÆ–&6´6öÖÒ†æG&ö–C¢&ööÆVâ’Ò'V–ÆD§6öäö&¦V7B°¢fÂ–BÒ66÷VçD–B‚¢fÂ¶W’Ò6öö¶–UfÇVR‚'×W6–5ö¶W’"Â'Õö¶W—7B"’æ÷$V×G’‚¢–b†æG&ö–B’°¢WB‚&7B"Â“²WB‚&7b"Â#ó3óS‚“²WB‚'b"Â#ó3óS‚¢WB‚'FÖT”B"Â'×W6–2"“²WB‚&6†–B"Â#3SR"¢ÒVÇ6R°¢vV$6öÖÒ‚’æf÷$V6‚²†æÖRÂfÇVR’ÓâWB†æÖRÂfÇVR’Ğ¢WB‚&u÷F²"Â†6ƒ32†¶W’’“²WB‚&u÷FµöæWuó##32"Â†6ƒ32†¶W’’¢Ğ¢WB‚'V–â"Â–B“²WB‚'"Â–B“²WB‚&WF‡7B"Â¶W’¢WB‚'FÖTÆöv–åG—R"Â6öö¶–UfÇVR‚'FÖTÆöv–åG—R"’ó¢#"¢Ğ ¢&—fFRgVâf–æEG&6·2‡&ö÷C¢§6öäVÆVÖVçB“¢Æ—7CÅG&6³âÒvÆ´ö&¦V7G2‡&ö÷B’æÖæ÷DçVÆÂ²fÇVRÓà¢fÂÖ–BÒf—'7EW6&ÆU6öætÖ–B‡fÇVRç7G&–ær‚&Ö–B"’ÂfÇVRç7G&–ær‚'6öævÖ–B"’ÂfÇVRç7G&–ær‚'6öæuöÖ–B"’¢fÂ6–ævW'2ÒfÇVU²'6–ævW"%Ò3ò§6öä'&¢fÂÆ'VÒÒfÇVU²&Æ'VÒ%Ò3ò§6öäö&¦V7@¢–b‚—5W6&ÆU6öætÖ–B†Ö–B’ÇÂ6–ævW'2ÓÒçVÆÂÇÂÆ'VÒÓÒçVÆÂ’&WGW&äÖæ÷DçVÆÂçVÆÀ¢fÂf–ÆRÒfÇVU²&f–ÆR%Ò3ò§6öäö&¦V7Bó¢§6öäö&¦V7B†V×G”Ö‚’¢fÂ’ÒfÇVU²'’%Ò3ò§6öäö&¦V7Bó¢§6öäö&¦V7B†V×G”Ö‚’¢fÂçVÖW&–4–BÒfÇVRæÆöær‚&–B"¢G&6²€¢–BÒÖ–BÂF—FÆRÒfÇVRç7G&–ær‚'F—FÆR"’æ–d&Ææ²²fÇVRç7G&–ær‚&æÖR"’ÒÀ¢'F—7G2Ò6–ævW'2æÖæ÷DçVÆÂ²†—B3ò§6öäö&¦V7B“òç7G&–ær‚&æÖR"“òçF¶T–b…7G&–æs£¦—4æ÷D&Ææ²’ÒÀ¢Æ'VÒÒÆ'VÒç7G&–ær‚'F—FÆR"’æ–d&Ææ²²Æ'VÒç7G&–ær‚&æÖR"’ÒÀ¢'Gv÷&µW&ÂÒ×W6–4Æ'VÔ'Gv÷&µW&Â†Æ'VÒç7G&–ær‚&Ö–B"’’æ–d&Ææ²²×W6–56öæt'Gv÷&µW&Â†Ö–B’ÒÀ¢Æ–&ÆRÒfÇVRæ–çB‚&—6öæÇ’"’ÓÒbb’æ–çB‚'•÷Æ’"’ÓÒÀ¢VÆ—F–W2Ò'6UVÆ—G”–G2‡fÇVRÂf–ÆR’À¢çVÖW&–4–BÒçVÖW&–4–BÂÖVF–Ö–BÒf–ÆRç7G&–ær‚&ÖVF–öÖ–B"’Â6öæuG—RÒfÇVRæ–çB‚'G—R"’À¢&WV—&W5f—ÒfÇVRæ–çB‚&—6öæÇ’"’ÒÇÂ’æ–çB‚'•÷Æ’"’Ò ¢¢ÒæF—7F–æ7D'’²—Bæ–BÒçFôÆ—7B‚ ¢&—fFRgVâf–æD6öÆÆV7F–öç2‡&ö÷C¢§6öäVÆVÖVçBÂ¶–æC¢7G&–ærÒ'Æ–Æ—7B"“¢Æ—7CÄ×W6–46öÆÆV7F–öãâÒvÆ´ö&¦V7G2‡&ö÷B’æÖæ÷DçVÆÂ²fÇVRÓà¢fÂF—FÆRÒfÇVRç7G&–ær‚'F—FÆR"’æ–d&Ææ²²fÇVRç7G&–ær‚&æÖR"’Òæ–d&Ææ²²fÇVRç7G&–ær‚&F—$æÖR"’Ğ¢fÂF—&V7F÷'”–BÒfÇVRç7G&–ær‚&F—$–B"’æ–d&Ææ²²fÇVRç7G&–ær‚&F—&–B"’Ğ¢fÂ–BÒv†Vâ†¶–æB’°¢&'F—7B"Â&Æ'VÒ"ÓâfÇVRç7G&–ær‚&Ö–B"¢VÇ6RÓâfÇVRç7G&–ær‚'F–B"’æ–d&Ææ²²fÇVRç7G&–ær‚&–B"’Òæ–d&Ææ²²F—&V7F÷'”–BĞ¢Ğ¢fÂÆöö·5&–v‡BÒv†Vâ†¶–æB’°¢&'F—7B"ÓâfÇVU²'6–ævW"%ÒÓÒçVÆÂbb‡fÇVU²'V–â%ÒÒçVÆÂÇÂfÇVU²'6–ævW$Ö–B%ÒÒçVÆÂÇÂfÇVU²&Ö–B%ÒÒçVÆÂ¢&Æ'VÒ"ÓâfÇVU²'F–ÖU÷V&Æ–2%ÒÒçVÆÂÇÂfÇVU²&Æ'VÔÖ–B%ÒÒçVÆÀ¢VÇ6RÓâfÇVU²'6öætçVÒ%ÒÒçVÆÂÇÂfÇVU²'6öævçVÒ%ÒÒçVÆÂÇÂfÇVU²&F—$–B%ÒÒçVÆÂÇÂfÇVU²&F—&–B%ÒÒçVÆÀ¢Ğ¢–b‚Æöö·5&–v‡BÇÂ–Bæ—4&Ææ²‚’ÇÂF—FÆRæ—4&Ææ²‚’’çVÆÂVÇ6R×W6–46öÆÆV7F–öâ€¢–BÂF—FÆRÀ¢æ÷&ÖÆ—¦T‡GG5W&Â‡fÇVRç7G&–ær‚'–5W&Â"’æ–d&Ææ²²fÇVRç7G&–ær‚'–7W&Â"’Òæ–d&Ææ²²fÇVRç7G&–ær‚'–2"’Ò’À¢fÇVRæ–çB‚'6öætçVÒ"’çF¶T–b²—BâÒó¢fÇVRæ–çB‚'6öævçVÒ"’À¢F—&V7F÷'”–Bæ–d&Ææ²²–BÒÀ¢¢ÒæF—7F–æ7D'’²—Bæ–BÒçFôÆ—7B‚ ¢&—fFRgVâvÆ´ö&¦V7G2†VÆVÖVçC¢§6öäVÆVÖVçB“¢6WVVæ6SÄ§6öäö&¦V7CâÒ6WVVæ6R°¢v†Vâ†VÆVÖVçB’°¢—2§6öäö&¦V7BÓâ²––VÆB†VÆVÖVçB“²VÆVÖVçBçfÇVW2æf÷$V6‚²––VÆDÆÂ‡vÆ´ö&¦V7G2†—B’’ÒĞ¢—2§6öä'&’ÓâVÆVÖVçBæf÷$V6‚²––VÆDÆÂ‡vÆ´ö&¦V7G2†—B’’Ğ¢VÇ6RÓâVæ—@¢Ğ¢Ğ ¢&—fFRgVâ7G&VÕF‚†FF¢§6öäVÆVÖVçB“¢7G&VÕFƒòÒvÆ´ö&¦V7G2†FF’æf—'7Dæ÷DçVÆÄöd÷$çVÆÂ²—FVÒÓà¢Æ—7Döb‚'W&Â"Â'v–f—W&Â"Â&fÆ÷wW&Â"Â&÷“#†·W&Â"Â&÷““f·W&Â"¢æf—'7Dæ÷DçVÆÄöd÷$çVÆÂ²¶W’Óâ—FVÒç7G&–ær†¶W’’çF¶T–b…7G&–æs£¦—4æ÷D&Ææ²“òæÆWB²7G&VÕF‚†—BÂ¶W’’ÒĞ¢Ğ ¢&—fFRgVâö&¢‡f&&rVçG&–W3¢—#Å7G&–ærÂç“óâ“¢§6öäö&¦V7BÒ'V–ÆD§6öäö&¦V7B²VçG&–W2æf÷$V6‚²†²Âb’ÓâWB†²Âç’‡b’’ÒĞ¢&—fFRgVâç’‡fÇVS¢ç“ò“¢§6öäVÆVÖVçBÒv†Vâ‡fÇVR’°¢çVÆÂÓâ§6öäçVÆÃ²—2§6öäVÆVÖVçBÓâfÇVS²—27G&–ærÓâ§6öå&–Ö—F—fR‡fÇVR“²—2çVÖ&W"Óâ§6öå&–Ö—F—fR‡fÇVR“²—2&ööÆVâÓâ§6öå&–Ö—F—fR‡fÇVR¢—2ÖÂ¢Â£âÓâ'V–ÆD§6öäö&¦V7B²fÇVRæf÷$V6‚²†²Âb’ÓâWB†²çFõ7G&–ær‚’Âç’‡b’’ÒĞ¢—2—FW&&ÆSÂ£âÓâ'V–ÆD§6öä'&’²fÇVRæf÷$V6‚²FB†ç’†—B’’ÒĞ¢VÇ6RÓâ§6öå&–Ö—F—fR‡fÇVRçFõ7G&–ær‚’¢Ğ¢&—fFRgVâ§6öäö&¦V7Bç7G&–ær†¶W“¢7G&–ær’Ò‡F†—5¶¶W•Ò3ò§6öå&–Ö—F—fR“òæ6öçFVçD÷$çVÆÂæ÷$V×G’‚¢&—fFRgVâ§6öäö&¦V7Bæ–çB†¶W“¢7G&–ær’Ò‡F†—5¶¶W•Ò3ò§6öå&–Ö—F—fR“òæ–çD÷$çVÆÂó¢ ¢&—fFRgVâ§6öäö&¦V7BæÆöær†¶W“¢7G&–ærÂFVfVÇC¢ÆöærÒ’Ò‡F†—5¶¶W•Ò3ò§6öå&–Ö—F—fR“òæÆöæt÷$çVÆÂó¢FVfVÇ@¢&—fFRgVâFV6öFUFW‡B‡fÇVS¢7G&–ær“¢7G&–ærÒFV6öFUÇ—&–5FW‡B‡fÇVR¢&—fFRgVâ6fVB†¶W“¢7G&–ærÂf7F÷'“¢‚’Óâ7G&–ær“¢7G&–ærÒ&Vg2ævWE7G&–ær†¶W’ÂçVÆÂ’ó¢f7F÷'’‚’æÇ6ò²&Vg2æVF—B‚’çWE7G&–ær†¶W’Â—B’æÇ’‚’Ğ¢&—fFRgVâ†6ƒ32‡fÇVS¢7G&–ær“¢–çBÒfÇVRæföÆBƒS3ƒ’²†6‚Â6†"Óâ†6‚²††6‚6†ÂR’²6†"æ6öFRÒæBƒvffffff`¢&—fFRgVâ6öö¶–UfÇVR‡f&&ræÖW3¢7G&–ær“¢7G&–æsòÒ6öö¶–R‚“òç7Æ—B‚s²r“òæÖ…7G&–æs£§G&–Ò“òæf—'7Dæ÷DçVÆÄöd÷$çVÆÂ²'BÓâæÖW2æf—'7D÷$çVÆÂ²'Bç7F'G5v—F‚‚"F—CÒ"’ÓòæÆWB²'Bç7V'7G&–ætgFW"‚sÒr’ÒĞ¢&—fFRgVâ66÷VçD–B‚’Ò6öö¶–UfÇVR‚'×W6–5÷V–â"Â'V–â"Â'w‡V–â"’æ÷$V×G’‚’çG&–Õ7F'B‚vòr¢&—fFRgVâ&WV—&TÆöv–â‚’²–b†6öö¶–R‚’æ—4çVÆÄ÷$&Ææ²‚’’W'&÷"‚.Šû~XXhš¾zy›¾[ÙR"’Ğ¢6ö×æ–öâö&¦V7B°¢&—fFRfÂ¥4ôåôÔTD”Ò&Æ–6F–öâö§6öã²6†'6WC×WFbÓ‚"çFôÖVF–G—R‚¢&—fFR6öç7BfÂtT%õTÒ$Ö÷¦–ÆÆóRã„Æ–çWƒ²æG&ö–B’ÆUvV$¶—BóS3rã3b6‡&öÖRó#Öö&–ÆR6f&’óS3rã3b ¢Ğ§Ğ