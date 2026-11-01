package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.model.MusicCollection
import kotlinx.serialization.json.*

private val playlistListKeys = listOf("v_list", "v_playlist", "list", "disslist")

internal data class QqPlaylistPage(val items: JsonArray, val total: Int?, val hasMore: Boolean?) {
    fun nextOffset(offset: Int, pageSize: Int): Int? {
        if (items.isEmpty()) {
            if ((total ?: 0) > offset || hasMore == true) {
                throw QqResponseException("QQ 音乐收藏歌单未完整返回，请重试")
            }
            return null
        }
        val next = offset + items.size
        return next.takeIf { when {
            total != null -> next < total
            hasMore != null -> hasMore
            else -> items.size >= pageSize
        } }
    }
}

internal fun parseQqPlaylistPage(root: JsonObject): QqPlaylistPage {
    fun containers(value: JsonObject): Sequence<JsonObject> = sequence {
        yield(value)
        value.values.filterIsInstance<JsonObject>().forEach { yieldAll(containers(it)) }
    }
    val container = containers(root).firstOrNull { item -> playlistListKeys.any { item[it] is JsonArray } }
        ?: throw QqResponseException("QQ 音乐响应缺少收藏歌单列表，请重试")
    val items = playlistListKeys.firstNotNullOf { container[it] as? JsonArray }
    fun number(value: JsonObject): Int? = listOf("total", "total_num", "totalNum", "totalnum").firstNotNullOfOrNull {
        (value[it] as? JsonPrimitive)?.intOrNull?.takeIf { count -> count >= 0 }
    }
    val more = (container["hasmore"] ?: container["hasMore"] ?: root["hasmore"] ?: root["hasMore"]) as? JsonPrimitive
    return QqPlaylistPage(items, number(container) ?: number(root), more?.booleanOrNull ?: more?.intOrNull?.let { it != 0 })
}

internal suspend fun collectQqPlaylistPages(
    pageSize: Int = 100,
    maxPages: Int = 20,
    fetchPage: suspend (offset: Int, size: Int) -> JsonObject,
): List<MusicCollection> {
    require(pageSize > 0 && maxPages > 0)
    val playlists = linkedMapOf<String, MusicCollection>()
    val seenPages = mutableSetOf<JsonArray>()
    var offset = 0
    repeat(maxPages) {
        val page = parseQqPlaylistPage(fetchPage(offset, pageSize))
        if (page.items.isNotEmpty() && !seenPages.add(page.items)) {
            throw QqResponseException("QQ 音乐重复返回同一页收藏歌单，请重试")
        }
        parseFavoritePlaylists(page.items).forEach { playlists.putIfAbsent(it.directoryId, it) }
        offset = page.nextOffset(offset, pageSize) ?: return playlists.values.toList()
    }
    throw QqResponseException("收藏歌单超过单次加载上限，请重试")
}
