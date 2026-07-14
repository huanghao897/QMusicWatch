package com.ronan.qmusicwatch.network

import android.content.Context
import android.os.Build
import android.util.Base64
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

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
            id, title, item.text("picUrl").ifBlank { item.text("picurl") }.replace("http://", "https://"),
            item.number("songNum").takeIf { it > 0 } ?: item.number("songnum"), dirId, owned,
        )
    }.distinctBy { it.directoryId }.toList()
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
    val identity = all.firstOrNull { (_, item) -> item.value("nick", "nickname", "nickName", "userName") != null }?.second
    val name = identity?.value("nick", "nickname", "nickName", "userName").orEmpty()
    val avatarKeys = arrayOf("logo", "logoUrl", "headurl", "headUrl", "headpic", "headPic", "head_pic", "headPicUrl", "avatarurl", "avatarUrl")
    val avatar = (identity?.value(*avatarKeys) ?: all.firstNotNullOfOrNull { (_, item) -> item.value(*avatarKeys) }).orEmpty().let { if (it.startsWith("//")) "https:$it" else it.replace("http://", "https://") }
    val now = System.currentTimeMillis() / 1000
    val memberships = all.mapNotNull { (path, item) ->
        val membershipPath = path.contains("vip", true) || path.contains("member", true)
        val raw = item.value(
            "isVip", "is_vip", "isSVip", "is_svip", "isSvip", "vip", "svip",
            "vipFlag", "vip_flag", "svipFlag", "svip_flag", "svip_status", "vipStatus",
            "vip_type", "vipType", "viptype", "music_vip_level", "green_vip_level",
            "luxury_vip_level", "super_vip_level", "svip_type", "svipType", "HugeVip", "LMFlag",
        )
        val enabled = raw?.let { it == "true" || (it.toLongOrNull() ?: 0) > 0 }
        val end = item.value(
            "HugeVipEnd", "LMEnd", "vipEndTime", "vip_end_time", "vip_endtime", "svipEndTime",
            "vipExpireTime", "vipExpireDate", "expireTime", "expire_time", "expireDate", "expire_date",
            "endTime", "end_time", "endDate",
        )?.let(::profileEpoch)
        var label = item.value("vipName", "vip_name", "vipLevelName", "levelName", "svipName", "name", "title").orEmpty()
        val pathAndLabel = "$path $label"
        label = when {
            pathAndLabel.contains("听书", true) || pathAndLabel.contains("book", true) -> "听书会员"
            pathAndLabel.contains("svip", true) || pathAndLabel.contains("超级", true) || item.value("isSVip", "is_svip", "isSvip", "svip", "huge_vip", "svipFlag", "svip_flag", "svip_status")?.let { it == "true" || (it.toLongOrNull() ?: 0) > 0 } == true -> "超级会员（SVIP）"
            pathAndLabel.contains("绿钻", true) || pathAndLabel.contains("green", true) || pathAndLabel.contains("luxury", true) -> "豪华绿钻"
            label.contains("会员") || label.contains("vip", true) -> label
            else -> ""
        }
        if (!membershipPath && end == null && label.isBlank()) null else Triple(enabled, end, label)
    }
    val active = memberships.filter { it.first == true || (it.second ?: 0) > now }.maxByOrNull { it.second ?: 0 }
    val expire = active?.second ?: memberships.mapNotNull { it.second }.maxOrNull()
    val isVip = when { active != null -> true; memberships.any { it.first == false } -> false; else -> null }
    val labels = memberships.map { it.third }.filter(String::isNotBlank)
    val vipName = if (active == null) "" else labels.firstOrNull { it.contains("超级") } ?: labels.firstOrNull() ?: "QQ 音乐会员"
    return UserProfile(name, avatar, isVip, expire, vipName).takeIf { it.displayName.isNotBlank() || it.avatarUrl.isNotBlank() || it.isVip != null || it.vipExpireAt != null }
}

private fun profileEpoch(value: String): Long? {
    val text = value.trim()
    val date = when {
        text.length >= 10 && text[4] in "-/" && text[7] in "-/" -> text.take(10).replace('/', '-')
        text.length == 8 && text.startsWith("20") && text.all(Char::isDigit) -> "${text.take(4)}-${text.substring(4, 6)}-${text.takeLast(2)}"
        else -> null
    }
    date?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false }.parse(it)?.time?.div(1000) }.getOrNull() }?.let { return it }
    return text.toLongOrNull()?.let { if (it > 10_000_000_000L) it / 1000 else it }?.takeIf { it >= 946_684_800L }
}

internal fun mergeUserProfiles(values: List<UserProfile>): UserProfile? {
    if (values.isEmpty()) return null
    val expire = values.mapNotNull(UserProfile::vipExpireAt).maxOrNull()
    val isVip = when { values.any { it.isVip == true } || expire?.let { it > System.currentTimeMillis() / 1000 } == true -> true; values.any { it.isVip == false } -> false; else -> null }
    return UserProfile(values.firstNotNullOfOrNull { it.displayName.takeIf(String::isNotBlank) }.orEmpty(), values.firstNotNullOfOrNull { it.avatarUrl.takeIf(String::isNotBlank) }.orEmpty(), isVip, expire, values.firstNotNullOfOrNull { it.takeIf { profile -> profile.isVip == true }?.vipName?.takeIf(String::isNotBlank) } ?: values.firstNotNullOfOrNull { it.vipName.takeIf(String::isNotBlank) }.orEmpty())
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
    val mid = text("songmid").ifBlank { text("mid") }; val title = text("songname").ifBlank { text("title") }
    if (mid.isBlank() || title.isBlank()) return null
    val pay = item["pay"] as? JsonObject ?: JsonObject(emptyMap())
    fun pay(name: String) = (pay[name] as? JsonPrimitive)?.intOrNull ?: 0
    val albumMid = text("albummid")
    return Track(mid, title, (item["singer"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }, text("albumname"), albumMid.takeIf(String::isNotBlank)?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }.orEmpty(), true, buildList { if (number("size128") > 0) add("128"); if (number("size320") > 0) add("320") }.ifEmpty { listOf("128") }, numericId = number("songid"), songType = number("type").toInt(), requiresVip = text("isonly") == "1" || pay("payplay") != 0 || pay("pay_play") != 0)
}

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

    suspend fun finishQrLogin(temporaryCookie: String): String = withContext(Dispatchers.IO) {
        fun part(name: String) = temporaryCookie.split(';').map(String::trim)
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
        val temporaryKey = part("qqmusic_key")
        val temporaryUin = part("qqmusic_uin")
        val qrCodeId = part("qrcode_id")
        if (temporaryKey.isBlank() || temporaryUin.isBlank() || qrCodeId.isBlank()) error("扫码结果不完整，请刷新二维码重试")

        val data = post(
            obj("ct" to 11, "cv" to 20_030_508),
            "music.login.LoginServer", "Login",
            obj(
                "loginType" to 6, "needCookie" to 1, "tmeAppID" to "qqmusic",
                "str_musicid" to temporaryUin, "qrCodeID" to qrCodeId, "token" to temporaryKey
            ),
            temporaryCookie
        )
        val key = data.string("musickey")
        val musicId = data.string("musicid").ifBlank { data.long("musicid").takeIf { it > 0 }?.toString().orEmpty() }
        if (key.isBlank() || musicId.isBlank()) error("QQ 音乐没有返回有效登录凭据，请重新扫码")
        listOfNotNull(
            "login_type=2", "tmeLoginMethod=3", "uin=o$musicId", "qqmusic_uin=$musicId",
            data.string("encryptUin").takeIf(String::isNotBlank)?.let { "euin=$it" },
            "tmeLoginType=${data.string("loginType").ifBlank { "1" }}",
            "wxuin=o$musicId",
            data.string("openid").takeIf(String::isNotBlank)?.let { "wxopenid=$it" },
            "qm_keyst=$key", "p_lskey=$key", "qqmusic_key=$key",
            data.string("refresh_key").takeIf(String::isNotBlank)?.let { "refresh_key=$it" }
        ).joinToString("; ")
    }

    suspend fun home(): HomeData = withContext(Dispatchers.IO) {
        val dailyData = api(
            "newsong.NewSongServer", "get_new_song_info", obj("type" to 5)
        )
        HomeData(findTracks(dailyData), emptyList())
    }

    suspend fun searchTracks(query: String, cursor: String? = null): PagedTracks = withContext(Dispatchers.IO) {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val items = webSearch(query, page, 0)["song"]?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        val tracks = items.mapNotNull { it as? JsonObject }.mapNotNull(::parseSearchTrack)
        PagedTracks(tracks, (page + 1).toString().takeIf { tracks.size == 20 })
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
        PagedCollections(collections, (page + 1).toString().takeIf { collections.size == 20 })
    }

    suspend fun lyrics(id: String): LyricsData = withContext(Dispatchers.IO) {
        val data = webApi(
            "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo",
            obj("songMid" to id, "crypt" to 0, "qrc" to 1, "qrc_t" to 0, "trans" to 1, "trans_t" to 0, "roma" to 0, "roma_t" to 0, "type" to 1, "ct" to 24, "cv" to 4_747_474)
        )
        LyricsData(
            decodeText(data.string("lyric")),
            decodeText(data.string("trans")).ifBlank { null },
            decodeText(data.string("qrc").ifBlank { data.string("qrcLyric") }).ifBlank { null },
        )
    }

    suspend fun stream(track: Track, quality: String): StreamData = withContext(Dispatchers.IO) {
        requireLogin()
        AppLog.write("STREAM", "request track=${track.id} preferred=$quality vip=${track.requiresVip}")
        val complete = if (track.mediaMid.isBlank()) trackDetail(track.id) else track
        val qualities = if (quality == "320" && "320" in complete.qualities) listOf("320", "128") else listOf("128")
        qualities.forEach { requested ->
            val filename = "${if (requested == "320") "M800" else "M500"}${complete.mediaMid}.mp3"
            val param = obj(
                "uin" to accountId(), "filename" to listOf(filename), "guid" to guid,
                "songmid" to listOf(complete.id), "songtype" to listOf(complete.songType),
                "loginflag" to 1, "platform" to "20", "ctx" to 0
            )
            listOf(
                Triple(playbackComm(android = false), "vkey.GetVkeyServer", "CgiGetVkey"),
                Triple(playbackComm(android = true), "music.vkey.GetVkey", "UrlGetVkey"),
            ).forEach attempt@{ (comm, module, method) ->
                val data = runCatching { post(comm, module, method, param, tolerateBusinessError = true) }.getOrNull() ?: return@attempt
                val purl = streamPath(data)
                if (purl.isNotBlank()) {
                    val base = walkObjects(data).firstNotNullOfOrNull { item ->
                        (item["sip"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                    }.orEmpty().replace("http://", "https://")
                    val url = if (purl.startsWith("http")) purl else "${base.ifBlank { "https://isure.stream.qqmusic.qq.com/" }}${if (base.endsWith('/')) "" else "/"}$purl"
                    AppLog.write("STREAM", "issued quality=$requested via=$module")
                    return@withContext StreamData(url, requested, System.currentTimeMillis() + data.long("expiration", 3600) * 1000)
                }
            }
        }
        AppLog.write("STREAM", "no-url track=${track.id} vip=${complete.requiresVip}")
        error(if (complete.requiresVip) "这首歌需要 VIP 或购买" else "QQ 音乐未提供播放地址，可能存在版权、地区或账号权益限制")
    }

    suspend fun library(): LibraryData = withContext(Dispatchers.IO) {
        requireLogin()
        val playlists = api("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", obj("uin" to accountId()))
        val liked = api(
            "music.srfDissInfo.DissInfo", "CgiGetDiss",
            obj("disstid" to 0, "dirid" to 201, "tag" to true, "song_begin" to 0, "song_num" to 100, "userinfo" to true, "orderlist" to true)
        )
        LibraryData(findTracks(liked), parseAccountPlaylists(playlists).filterNot { it.directoryId == "201" })
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
        val stream = stream(track, "128")
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
        api(
            "music.musicasset.SongFavWrite", if (liked) "CgiAddSongFav" else "CgiDelSongFav",
            obj("v_songId" to listOf(track.numericId.takeIf { it > 0 } ?: track.id.toLongOrNull().orEmptyLong()))
        )
        Ack(true)
    }

    suspend fun collection(type: String, collection: MusicCollection): CollectionDetail = withContext(Dispatchers.IO) {
        val data = when (type) {
            "album" -> api("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", obj("albumMid" to collection.id, "begin" to 0, "num" to 100, "order" to 2))
            "artist" -> api("musichall.song_list_server", "GetSingerSongList", obj("singerMid" to collection.id, "begin" to 0, "num" to 100, "order" to 1))
            else -> api("music.srfDissInfo.DissInfo", "CgiGetDiss", obj("disstid" to (collection.id.toLongOrNull() ?: 0), "dirid" to (collection.directoryId.toLongOrNull() ?: 0), "tag" to true, "song_begin" to 0, "song_num" to 100, "userinfo" to true, "orderlist" to true))
        }
        val title = walkObjects(data).firstNotNullOfOrNull { it.string("title").ifBlank { it.string("name") }.takeIf(String::isNotBlank) } ?: "详情"
        CollectionDetail(title, findTracks(data))
    }

    suspend fun createPlaylist(title: String): MusicCollection = withContext(Dispatchers.IO) {
        requireLogin()
        val data = api("music.musicasset.PlaylistBaseWrite", "AddPlaylist", obj("dirName" to title))
        val item = walkObjects(data).firstOrNull { it["dirId"] != null || it["tid"] != null } ?: JsonObject(emptyMap())
        val dirId = item.string("dirId")
        MusicCollection(item.string("tid").ifBlank { dirId }, title, directoryId = dirId)
    }

    suspend fun renamePlaylist(id: String, title: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        api("music.musicasset.PlaylistBaseWrite", "ModifyPlaylist", obj("dirId" to id.toLongOrNull(), "dirName" to title))
        Ack(true)
    }

    suspend fun deletePlaylist(id: String): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        api("music.musicasset.PlaylistBaseWrite", "DelPlaylist", obj("dirId" to id.toLongOrNull()))
        Ack(true)
    }

    suspend fun changePlaylistTrack(id: String, track: Track, add: Boolean): Ack = withContext(Dispatchers.IO) {
        requireLogin()
        api(
            "music.musicasset.PlaylistDetailWrite", if (add) "AddSonglist" else "DelSonglist",
            obj("dirId" to id.toLongOrNull(), "tid" to 0, "bFmtUtf8" to true, "v_songInfo" to listOf(mapOf("songId" to track.numericId, "songType" to track.songType)))
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
        return MusicCollection(id, title, item.string("singerPic").ifBlank { item.string("imgurl") }.ifBlank { item.string("pic") }.replace("http://", "https://"), count)
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
            qualities = buildList { if (file.long("size_128mp3") > 0) add("128"); if (file.long("size_320mp3") > 0) add("320") }.ifEmpty { listOf("128") },
            numericId = numericId, mediaMid = file.string("media_mid"), songType = value.int("type"),
            requiresVip = pay.int("pay_play") != 0
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
            value.string("picUrl").ifBlank { value.string("picurl") }.ifBlank { value.string("pic") }.replace("http://", "https://"),
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

    private fun streamPath(data: JsonElement): String = walkObjects(data).firstNotNullOfOrNull { item ->
        listOf("purl", "wifiurl", "flowurl", "opi128kurl", "opi96kurl")
            .firstNotNullOfOrNull { key -> item.string(key).takeIf(String::isNotBlank) }
    }.orEmpty()

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
    private fun Long?.orEmptyLong() = this ?: 0L

    companion object {
        private const val MUSIC_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val WEB_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
