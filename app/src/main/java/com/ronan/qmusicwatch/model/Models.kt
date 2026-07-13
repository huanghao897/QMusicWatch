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
    val directoryId: String = id,
)
@Serializable data class HomeData(val daily: List<Track>, val recommended: List<MusicCollection>)
@Serializable data class PagedTracks(val items: List<Track>, val nextCursor: String? = null)
@Serializable data class PagedCollections(val items: List<MusicCollection>, val nextCursor: String? = null)
@Serializable data class LyricsData(val original: String, val translation: String? = null)
@Serializable data class StreamData(val url: String, val quality: String, val expiresAt: Long)
@Serializable data class LibraryData(val liked: List<Track>, val playlists: List<MusicCollection>)
@Serializable data class SessionTokens(
    val accessToken: String = "", val refreshToken: String = "", val accountId: String,
    val provider: String = "qq", val upstreamCookie: String? = null,
)
@Serializable data class CookieExchange(val provider: String, val cookie: String)
@Serializable data class Ack(val accepted: Boolean)
@Serializable data class CollectionDetail(val title: String, val tracks: List<Track>)
@Serializable data class PlaybackSnapshot(val track: Track? = null, val queue: List<Track> = emptyList(), val positionMs: Long = 0)
@Serializable data class UserProfile(
    val displayName: String = "", val avatarUrl: String = "", val isVip: Boolean? = null,
    val vipExpireAt: Long? = null, val vipName: String = "",
)
