package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.login.MusicCookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal const val QMUSIC_SERVER_HOST = "heyboxlite.xyz"
internal const val QQ_MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
internal const val QQ_LEGACY_HOST = "c.y.qq.com"
internal const val QMUSIC_ARTWORK_HOST = "y.gtimg.cn"

private val qqAudioHostSuffixes = setOf("qqmusic.qq.com", "music.tc.qq.com")
private val qqImageHostSuffixes = setOf("gtimg.cn", "qlogo.cn", "qpic.cn")

private fun hostMatches(host: String, suffixes: Set<String>): Boolean {
    val value = host.lowercase().trimEnd('.')
    return suffixes.any { value == it || value.endsWith(".$it") }
}

internal fun normalizeQqHttpsUrl(value: String): String {
    val text = value.trim()
    return when {
        text.startsWith("//") -> "https:$text"
        text.startsWith("http://", ignoreCase = true) -> "https://${text.substringAfter("://")}"
        else -> text
    }
}

internal fun validateRefreshedCookie(staleCookie: String, refreshedCookie: String): String {
    val staleAccount = MusicCookie.accountId(staleCookie)
    val refreshedAccount = MusicCookie.accountId(refreshedCookie)
    require(staleAccount != null && staleAccount == refreshedAccount) {
        "刷新后的登录账号与当前账号不一致"
    }
    val playbackKey = refreshedCookie.split(';').asSequence()
        .map { it.trim().split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] in setOf("qm_keyst", "qqmusic_key", "p_lskey") }
        ?.get(1).orEmpty()
    require(
        playbackKey.isNotBlank() &&
            playbackKey.length <= 8192 &&
            playbackKey.none { it.code < 0x20 || it.code == 0x7f }
    ) { "刷新后的登录凭据不完整" }
    return refreshedCookie
}

internal fun qmusicAlbumArtworkUrl(albumMid: String): String {
    val id = albumMid.trim()
    if (!id.matches(Regex("[A-Za-z0-9]{1,64}"))) return ""
    return "https://$QMUSIC_ARTWORK_HOST/music/photo_new/T002R300x300M000$id.jpg"
}

/** A song MID cannot safely be substituted for the album MID used by QQ's cover CDN. */
internal fun qmusicSongArtworkUrl(songMid: String): String {
    val id = songMid.trim()
    if (!id.matches(Regex("[A-Za-z0-9]{1,48}")) || !isUsableQqSongMid(id)) return ""
    return ""
}

internal fun qmusicAvatarUrl(uin: String): String {
    val id = uin.trim().trimStart('o')
    if (!id.matches(Regex("\\d{1,24}"))) return ""
    return "https://q1.qlogo.cn/g?b=qq&nk=$id&s=140"
}

internal fun trustedQMusicMediaUrl(value: String): String {
    val normalized = normalizeQqHttpsUrl(value)
    if (normalized.length !in 1..8192) return ""
    val url = normalized.toHttpUrlOrNull() ?: return ""
    if (
        url.scheme != "https" ||
        url.port != 443 ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.fragment != null ||
        !hostMatches(url.host, qqAudioHostSuffixes) ||
        url.encodedPath.isBlank() ||
        url.encodedPath == "/"
    ) return ""
    return normalized
}

internal fun trustedQMusicArtworkUrl(value: String): String {
    val normalized = normalizeQqHttpsUrl(value)
    val url = normalized.toHttpUrlOrNull() ?: return ""
    if (
        url.scheme != "https" ||
        url.host != QMUSIC_ARTWORK_HOST ||
        url.port != 443 ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.query != null ||
        url.fragment != null
    ) return ""
    return normalized.takeIf {
        url.encodedPath.matches(
            Regex("/music/photo_new/T002R(?:150x150|300x300)M000[A-Za-z0-9]{1,64}\\.jpg")
        )
    }.orEmpty()
}

internal fun trustedQMusicImageUrl(value: String): String {
    val normalized = normalizeQqHttpsUrl(value)
    if (normalized.length !in 1..4096) return ""
    trustedQMusicArtworkUrl(normalized).takeIf(String::isNotBlank)?.let { return it }
    val url = normalized.toHttpUrlOrNull() ?: return ""
    if (
        url.scheme != "https" ||
        url.port != 443 ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.fragment != null ||
        !hostMatches(url.host, qqImageHostSuffixes) ||
        url.encodedPath.isBlank()
    ) return ""
    return normalized
}

internal fun preferDirectQMusicArtworkUrl(value: String): String = trustedQMusicImageUrl(value)

internal fun resolveQqStreamUrl(path: String, bases: List<String>): String {
    trustedQMusicMediaUrl(path).takeIf(String::isNotBlank)?.let { return it }
    val relative = path.trim()
    if (relative.isBlank() || relative.startsWith("//") || "://" in relative) return ""
    for (base in bases) {
        val trustedBase = normalizeQqHttpsUrl(base).toHttpUrlOrNull() ?: continue
        if (trustedQMusicMediaUrl(trustedBase.newBuilder().encodedPath("/probe").query(null).build().toString()).isBlank()) continue
        val resolved = trustedBase.resolve(relative)?.toString().orEmpty()
        trustedQMusicMediaUrl(resolved).takeIf(String::isNotBlank)?.let { return it }
    }
    return ""
}

internal fun Request.Builder.withQqMusicMediaHeaders(): Request.Builder =
    header("Referer", "https://y.qq.com/")
        .header("Origin", "https://y.qq.com")
        .header("User-Agent", "QMusicWatch")

internal fun safeLocalOrQqMediaUri(value: String): String {
    val trimmed = value.trim()
    val scheme = trimmed.substringBefore(':', "").lowercase()
    return when (scheme) {
        "file", "content", "android.resource" -> trimmed
        "https", "http" -> trustedQMusicMediaUrl(trimmed)
        else -> ""
    }
}

internal fun safeLocalOrArtworkUri(value: String): String {
    val trimmed = value.trim()
    val scheme = trimmed.substringBefore(':', "").lowercase()
    return when (scheme) {
        "file", "content", "android.resource" -> trimmed
        "https", "http" -> preferDirectQMusicArtworkUrl(trimmed)
        else -> ""
    }
}
