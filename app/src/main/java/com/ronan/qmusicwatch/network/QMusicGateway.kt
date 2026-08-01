package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.BuildConfig
import com.ronan.qmusicwatch.login.MusicCookie
import com.ronan.qmusicwatch.model.SessionTokens
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val QMUSIC_SERVER_HOST = "heyboxlite.xyz"
internal const val QMUSIC_ARTWORK_HOST = "y.gtimg.cn"

internal fun sessionNeedsGatewayCredentialRefresh(
    session: SessionTokens?,
    gatewayHost: String = QMUSIC_SERVER_HOST,
): Boolean = session != null && session.gatewayHost != gatewayHost

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
    require(playbackKey.isNotBlank() && playbackKey.length <= 8192 && playbackKey.none { it.code < 0x20 || it.code == 0x7f }) {
        "刷新后的登录凭据不完整"
    }
    return refreshedCookie
}

private val configuredQMusicServerBaseUrl: HttpUrl by lazy {
    requireControlPlaneBaseUrl(BuildConfig.QMUSIC_SERVER_BASE_URL, BuildConfig.DEBUG)
}

internal fun qmusicServerEndpoint(path: String): HttpUrl =
    configuredQMusicServerBaseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()

internal fun qmusicAlbumArtworkUrl(albumMid: String): String {
    val id = albumMid.trim()
    if (!id.matches(Regex("[A-Za-z0-9]{1,64}"))) return ""
    return "https://$QMUSIC_ARTWORK_HOST/music/photo_new/T002R300x300M000$id.jpg"
}

/**
 * Returns a restart-safe artwork URL when an old cached track does not contain
 * its album MID. Only this legacy resolution path still uses the gateway.
 */
internal fun qmusicSongArtworkUrl(songMid: String): String {
    val id = songMid.trim()
    if (!id.matches(Regex("[A-Za-z0-9]{1,48}")) || !isUsableQqSongMid(id)) return ""
    return qmusicServerEndpoint("api/qmusic-watch/gateway/artwork/album/QMWTRACK$id.jpg").toString()
}

internal fun qmusicAvatarUrl(uin: String): String {
    val id = uin.trim().trimStart('o')
    if (!id.matches(Regex("\\d{1,24}"))) return ""
    return qmusicServerEndpoint("api/qmusic-watch/gateway/avatar/qq/$id.jpg").toString()
}

internal fun trustedQMusicMediaUrl(
    value: String,
    baseUrl: HttpUrl = configuredQMusicServerBaseUrl,
): String {
    val url = value.trim().toHttpUrlOrNull() ?: return ""
    if (
        url.scheme != baseUrl.scheme ||
        url.host != baseUrl.host ||
        url.port != baseUrl.port ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.query != null ||
        url.fragment != null
    ) return ""
    val path = url.encodedPath
    val allowed = when {
        path.startsWith("/api/qmusic-watch/gateway/media/") ->
            url.pathSegments.size == 6 &&
                url.pathSegments[4].matches(Regex("[A-Za-z0-9_-]{24,96}")) &&
                url.pathSegments[5].matches(Regex("[A-Za-z0-9._-]{1,96}"))
        path.startsWith("/api/qmusic-watch/gateway/artwork/album/") ->
            url.pathSegments.size == 6 &&
                url.pathSegments.last().matches(Regex("[A-Za-z0-9]{1,64}\\.jpg"))
        path.startsWith("/api/qmusic-watch/gateway/avatar/qq/") ->
            url.pathSegments.size == 6 &&
                url.pathSegments.last().matches(Regex("\\d{1,24}\\.jpg"))
        else -> false
    }
    return value.trim().takeIf { allowed }.orEmpty()
}

internal fun trustedQMusicArtworkUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull() ?: return ""
    if (
        url.scheme != "https" ||
        url.host != QMUSIC_ARTWORK_HOST ||
        url.port != 443 ||
        url.username.isNotEmpty() ||
        url.password.isNotEmpty() ||
        url.query != null ||
        url.fragment != null
    ) return ""
    return value.trim().takeIf {
        url.encodedPath.matches(
            Regex("/music/photo_new/T002R(?:150x150|300x300)M000[A-Za-z0-9]{1,64}\\.jpg")
        )
    }.orEmpty()
}

internal fun trustedQMusicImageUrl(value: String): String {
    trustedQMusicArtworkUrl(value).takeIf(String::isNotBlank)?.let { return it }
    return trustedQMusicMediaUrl(value)
}

internal fun preferDirectQMusicArtworkUrl(value: String): String {
    val trusted = trustedQMusicImageUrl(value)
    val url = trusted.toHttpUrlOrNull() ?: return ""
    if (url.host != QMUSIC_SERVER_HOST || !url.encodedPath.startsWith("/api/qmusic-watch/gateway/artwork/album/")) {
        return trusted
    }
    val identifier = url.pathSegments.lastOrNull()?.removeSuffix(".jpg").orEmpty()
    return if (identifier.startsWith("QMWTRACK")) trusted else qmusicAlbumArtworkUrl(identifier)
}

internal fun safeLocalOrGatewayMediaUri(value: String): String {
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
