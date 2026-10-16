package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.model.Track
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

private val trackListKeys = listOf("songlist", "songList", "songInfoList", "songs", "list")

internal data class QqTrackPage(
    val items: JsonArray,
    val consumedCount: Int,
    val total: Int?,
    val hasMore: Boolean?,
) {
    fun nextOffset(offset: Int, requestedSize: Int): Int? {
        if (items.isEmpty()) return null
        val next = offset + consumedCount
        return next.takeIf {
            when {
                total != null -> next < total
                hasMore != null -> hasMore
                else -> items.size >= requestedSize
            }
        }
    }
}

internal fun parseQqTrackPage(root: JsonObject): QqTrackPage {
    fun containers(value: JsonObject): Sequence<JsonObject> = sequence {
        yield(value)
        value.values.filterIsInstance<JsonObject>().forEach { yieldAll(containers(it)) }
    }
    val container = containers(root).firstOrNull { item ->
        trackListKeys.any { item[it] is JsonArray }
    } ?: throw QqResponseException("QQ 音乐响应缺少歌曲列表，请稍后重试")
    val items = trackListKeys.firstNotNullOf { container[it] as? JsonArray }
    fun number(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        (container[name] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    }
    val total = number("total_song_num", "totalNum", "total", "totalnum", "totalSongNum")
    val more = (container["hasmore"] ?: container["hasMore"]) as? JsonPrimitive
    // Pagination must count upstream slots, not parsed/deduplicated tracks.
    // A page may contain unavailable entries or duplicate song MIDs.
    val consumed = maxOf(items.size, number("songlist_size") ?: 0)
    return QqTrackPage(items, consumed, total, more?.booleanOrNull ?: more?.intOrNull?.let { it != 0 })
}

internal suspend fun collectQqTrackPages(
    pageSize: Int = 100,
    maxPages: Int = 100,
    fetchPage: suspend (offset: Int, size: Int) -> JsonObject,
    parseTracks: (JsonElement) -> List<Track>,
): List<Track> {
    require(pageSize > 0 && maxPages > 0)
    val tracks = linkedMapOf<String, Track>()
    val seenPages = mutableSetOf<String>()
    var offset = 0
    repeat(maxPages) {
        val page = parseQqTrackPage(fetchPage(offset, pageSize))
        if (page.items.isEmpty()) {
            if ((page.total ?: 0) > offset || page.hasMore == true) {
                throw QqResponseException("QQ 音乐歌曲列表未完整返回，请稍后重试")
            }
            return tracks.values.toList()
        }
        val batch = parseTracks(page.items)
        if (batch.isNotEmpty()) {
            val signature = MessageDigest.getInstance("SHA-256")
                .digest(page.items.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (!seenPages.add(signature)) {
                throw QqResponseException("QQ 音乐重复返回同一页歌曲，请稍后重试")
            }
        }
        batch.forEach { tracks.putIfAbsent(it.id, it) }
        offset = page.nextOffset(offset, pageSize) ?: return tracks.values.toList()
    }
    throw QqResponseException("歌曲列表超过单次加载上限，请稍后重试")
}
