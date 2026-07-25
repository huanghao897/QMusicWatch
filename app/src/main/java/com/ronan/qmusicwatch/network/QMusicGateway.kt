package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val QMUSIC_SERVER_HOST = "203.160.55.168"

private val configuredQMusicServerBaseUrl: HttpUrl by lazy {
    requireControlPlaneBaseUrl(BuildConfig.QMUSIC_SERVER_BASE_URL, BuildConfig.DEBUG)
}

internal fun qmusicServerEndpoint(path: String): HttpUrl =
    configuredQMusicServerBaseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()

internal fun qmusicAlbumArtworkUrl(albumMid: String): String {
    val id = albumMid.trim()
    if (!id.matches(Regex("[A-Za-z0-9]{1,64}"))) return ""
    return qmusicServerEndpoint("api/qmusic-watch/gateway/artwork/album/$id.jpg").toString()
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

internal fun safeLocalOrGatewayUri(value: String): String {
    val trimmed = value.trim()
    val scheme = trimmed.substringBefore(':', "").lowercase()
    return when (scheme) {
        "file", "content", "android.resource" -> trimmed
        "https", "http" -> trustedQMusicMediaUrl(trimmed)
        else -> ""
    }
}
