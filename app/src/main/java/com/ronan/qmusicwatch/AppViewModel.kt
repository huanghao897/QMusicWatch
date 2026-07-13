package com.ronan.qmusicwatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronan.qmusicwatch.data.RecentEntity
import com.ronan.qmusicwatch.data.mergeRecent
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class AppUiState(
    val loading: Boolean = false, val message: String? = null, val home: HomeData? = null,
    val library: LibraryData? = null, val recent: List<Track> = emptyList(), val searchTracks: List<Track> = emptyList(),
    val searchCollections: List<MusicCollection> = emptyList(), val searchType: String = "track",
    val searchQuery: String = "", val searchCursor: String? = null, val searchLoading: Boolean = false,
    val qrStatus: String = "", val currentTrack: Track? = null,
    val lyrics: List<LyricLine> = emptyList(), val pendingSpeakerTrack: Track? = null,
    val detail: CollectionDetail? = null, val detailDirectoryId: String? = null, val playEvent: Long = 0,
    val diagnostic: String? = null, val profile: UserProfile? = null,
)

internal fun insertNext(queue: List<Track>, currentId: String?, track: Track): List<Track> {
    val items = queue.filterNot { it.id == track.id }.toMutableList()
    val current = items.indexOfFirst { it.id == currentId }
    items.add((current + 1).coerceIn(0, items.size), track)
    return items
}

internal fun nextQueueIndex(size: Int, current: Int, delta: Int, mode: String, ended: Boolean, shuffled: Int = -1): Int = when {
    size <= 0 -> -1
    ended && mode == "repeat_one" -> current.coerceIn(0, size - 1)
    mode == "shuffle" -> shuffled.takeIf { it in 0 until size && it != current } ?: current
    current + delta in 0 until size -> current + delta
    mode == "loop_all" -> if (delta > 0) 0 else size - 1
    else -> -1
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application as QMusicApplication
    private val _state = MutableStateFlow(AppUiState())
    val state = _state.asStateFlow()
    val downloads = graph.downloads.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val quality = graph.settings.quality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "128")
    val headphoneWarning = graph.settings.headphoneWarning.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoOpenPlayer = graph.settings.autoOpenPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val playMode = graph.settings.playMode.stateIn(viewModelScope, SharingStarted.Eagerly, "sequential")
    val lyricSize = graph.settings.lyricSize.stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val lyricTranslation = graph.settings.lyricTranslation.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lyricOriginal = graph.settings.lyricOriginal.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lyricOffset = graph.settings.lyricOffset.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val lyricAnimation = graph.settings.lyricAnimation.stateIn(viewModelScope, SharingStarted.Eagerly, "soft")
    val pureBlack = graph.settings.pureBlack.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lowPowerPlayer = graph.settings.lowPowerPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val wifiOnlyDownload = graph.settings.wifiOnlyDownload.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastSleepMinutes = graph.settings.lastSleepMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val dailyCount = graph.settings.dailyCount.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val searchHistory = graph.settings.searchHistory.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue = _queue.asStateFlow()
    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex = _queueIndex.asStateFlow()
    private val _queueReversed = MutableStateFlow(false)
    val queueReversed = _queueReversed.asStateFlow()
    val sleepRemaining = graph.playback.sleepRemaining
    private var pendingQueue: List<Track>? = null
    private var restoredPosition = 0L
    private val json = Json { ignoreUnknownKeys = true }
    private var currentSession = graph.vault.load()
    val signedIn get() = currentSession != null
    val accountId get() = currentSession?.accountId
    val loginProvider get() = currentSession?.provider ?: "qq"

    init {
        graph.playback.onEnded = ::advanceAfterEnd
        graph.playback.onError = { message -> _state.update { it.copy(message = message) } }
        viewModelScope.launch {
            runCatching { json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first()) }.getOrNull()?.let { snapshot ->
                _queue.value = snapshot.queue.distinctBy(Track::id)
                _queueReversed.value = snapshot.queueReversed
                restoredPosition = snapshot.positionMs
                snapshot.track?.let { track ->
                    _state.update { it.copy(currentTrack = track) }
                    _queueIndex.value = _queue.value.indexOfFirst { item -> item.id == track.id }
                }
            }
        }
        viewModelScope.launch { while (isActive) { delay(10_000); if (_state.value.currentTrack != null) persistSnapshot() } }
        loadHome()
    }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun fail(error: Throwable) {
        AppLog.write("ERROR", generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}:${it.message.orEmpty()}" })
        _state.update { it.copy(loading = false, searchLoading = false, message = error.message ?: "操作失败") }
    }

    fun loadHome() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        runCatching { graph.api.home() }.onSuccess { _state.update { s -> s.copy(loading = false, home = it) } }.onFailure { _state.update { s -> s.copy(loading = false) } }
    }
    fun completeOfficialLogin(provider: String, cookie: String) = viewModelScope.launch {
        _state.update { it.copy(qrStatus = "已确认，正在完成登录…") }
        runCatching {
            val finalCookie = graph.api.finishQrLogin(cookie)
            val account = com.ronan.qmusicwatch.login.MusicCookie.accountId(finalCookie)
                ?: error("登录响应缺少账号标识")
            SessionTokens(accountId = account, provider = provider, upstreamCookie = finalCookie)
        }.onSuccess { session ->
            graph.vault.save(session)
            currentSession = session
            _state.update { s -> s.copy(qrStatus = "登录成功", message = "登录成功") }
            loadHome(); loadProfile()
        }.onFailure { error ->
            _state.update { s -> s.copy(qrStatus = "登录失败，请返回刷新二维码") }
            fail(error)
        }
    }
    fun logout() { graph.vault.clear(); currentSession = null; android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush(); _state.update { it.copy(library = null, recent = emptyList(), profile = null, message = "已退出，离线文件已保留并锁定") } }
    fun loadLibrary() = viewModelScope.launch {
        if (!signedIn) return@launch fail(IllegalStateException("请先登录"))
        runCatching { graph.api.library() }.onSuccess { value -> _state.update { it.copy(library = value) } }.onFailure(::fail)
    }
    fun loadProfile() = viewModelScope.launch {
        if (!signedIn || _state.value.profile != null) return@launch
        runCatching { graph.api.profile() }.onSuccess { profile -> _state.update { it.copy(profile = profile) } }
            .onFailure { AppLog.write("PROFILE", "${it.javaClass.simpleName}:${it.message.orEmpty()}") }
    }
    fun diagnose() = viewModelScope.launch {
        _state.update { it.copy(message = "正在检查登录、歌单和播放地址…") }
        runCatching { graph.api.diagnose() }
            .onSuccess { result -> _state.update { it.copy(message = result, diagnostic = result) } }
            .onFailure { error -> _state.update { it.copy(diagnostic = "诊断失败：${error.message ?: "未知错误"}") }; fail(error) }
    }
    fun loadRecent() = viewModelScope.launch {
        val local = graph.db.recent().all().map { Track(it.trackId, it.title, it.artists.split(" / "), it.album, it.artworkUrl, playedAt = it.playedAt) }
        val cloud = if (signedIn) runCatching { graph.api.recent() }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(recent = mergeRecent(local, cloud)) }
    }
    fun search(query: String, type: String, loadMore: Boolean = false) = viewModelScope.launch {
        if (query.isBlank()) return@launch
        val append = loadMore && _state.value.searchQuery == query && _state.value.searchType == type
        val cursor = _state.value.searchCursor.takeIf { append } ?: if (loadMore) return@launch else null
        _state.update { it.copy(searchLoading = true) }
        if (!append) graph.settings.addSearchHistory(query)
        runCatching {
            if (type == "track") graph.api.searchTracks(query, cursor).let { Triple(it.items, emptyList<MusicCollection>(), it.nextCursor) }
            else graph.api.searchCollections(type, query, cursor).let { Triple(emptyList<Track>(), it.items, it.nextCursor) }
        }.onSuccess { (tracks, collections, next) -> _state.update { state -> state.copy(searchTracks = (if (append) state.searchTracks + tracks else tracks).distinctBy(Track::id), searchCollections = (if (append) state.searchCollections + collections else collections).distinctBy(MusicCollection::id), searchType = type, searchQuery = query, searchCursor = next, searchLoading = false) } }.onFailure(::fail)
    }
    fun clearSearchHistory() = viewModelScope.launch { graph.settings.clearSearchHistory() }
    fun requestPlay(track: Track, allowSpeaker: Boolean = false, sourceQueue: List<Track>? = null) = viewModelScope.launch {
        if (!track.playable && !track.requiresVip) return@launch fail(IllegalStateException("这首歌曲当前不可播放"))
        if (headphoneWarning.value && !allowSpeaker && !com.ronan.qmusicwatch.playback.hasPrivateAudioOutput(getApplication())) {
            pendingQueue = sourceQueue
            _state.update { it.copy(pendingSpeakerTrack = track) }; return@launch
        }
        val owner = accountId ?: return@launch fail(IllegalStateException("请先登录后播放"))
        runCatching {
            val local = graph.db.downloads().find(track.id, owner)?.takeIf { it.status == "complete" && File(it.filePath).exists() }
            val uri = local?.let { android.net.Uri.fromFile(File(it.filePath)).toString() } ?: graph.api.stream(track, preferredQuality(track)).url
            graph.playback.play(track.id, uri, track.title, track.artists.joinToString(" / "), track.artworkUrl)
            if (restoredPosition > 0 && _state.value.currentTrack?.id == track.id) graph.playback.seek(restoredPosition)
            graph.db.recent().upsert(RecentEntity(track.id, track.title, track.artists.joinToString(" / "), track.album, track.artworkUrl, System.currentTimeMillis()))
            runCatching { graph.api.lyrics(track.id) }.map { LrcParser.parse(it.original, it.translation) }.getOrDefault(emptyList())
        }.onSuccess { parsedLyrics ->
            sourceQueue?.takeIf(List<Track>::isNotEmpty)?.let { source ->
                _queue.value = source.distinctBy(Track::id)
                _queueReversed.value = false
            }
            if (_queue.value.none { it.id == track.id }) _queue.value = _queue.value + track
            _queueIndex.value = _queue.value.indexOfFirst { it.id == track.id }
            pendingQueue = null
            restoredPosition = 0
            _state.update { it.copy(currentTrack = track, lyrics = parsedLyrics, pendingSpeakerTrack = null, playEvent = System.nanoTime()) }
            persistSnapshot()
        }.onFailure(::fail)
    }
    fun continueOnSpeaker() { _state.value.pendingSpeakerTrack?.let { requestPlay(it, true, pendingQueue) } }
    fun dismissSpeakerPrompt() = _state.update { it.copy(pendingSpeakerTrack = null) }
    fun cache(track: Track, groupName: String = "单曲缓存") = viewModelScope.launch {
        val owner = accountId ?: return@launch fail(IllegalStateException("请先登录"))
        runCatching { graph.api.stream(track, preferredQuality(track)) }.onSuccess { graph.downloads.enqueue(track, owner, it.url, wifiOnlyDownload.value, groupName); _state.update { s -> s.copy(message = if (wifiOnlyDownload.value) "已加入缓存，等待 Wi-Fi" else "已加入离线缓存") } }.onFailure(::fail)
    }
    fun cacheAll(tracks: List<Track>, groupName: String = "播放列表缓存") = tracks.forEach { cache(it, groupName) }
    fun resumeDownload(item: com.ronan.qmusicwatch.data.DownloadEntity) = cache(Track(item.trackId, item.title, item.artists.split(" / "), artworkUrl = item.artworkUrl, qualities = listOf("128", "320")), item.groupName)
    fun pauseDownload(id: String) = viewModelScope.launch { accountId?.let { graph.downloads.pause(id, it) } }
    fun deleteDownload(id: String, owner: String) = viewModelScope.launch { graph.downloads.delete(id, owner) }
    fun deleteInvalidDownloads() = viewModelScope.launch {
        val owner = accountId ?: return@launch
        val count = graph.downloads.deleteInvalid(owner)
        _state.update { it.copy(message = if (count == 0) "没有失效缓存" else "已删除 $count 个失效缓存") }
    }
    fun like(track: Track, liked: Boolean) = viewModelScope.launch { runCatching { graph.api.like(track, liked) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun loadDetail(type: String, collection: MusicCollection, editable: Boolean = false) = viewModelScope.launch { runCatching { graph.api.collection(type, collection) }.onSuccess { value -> _state.update { it.copy(detail = value, detailDirectoryId = collection.directoryId.takeIf { editable }) } }.onFailure(::fail) }
    fun createPlaylist(title: String) = viewModelScope.launch { runCatching { graph.api.createPlaylist(title) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun renamePlaylist(id: String, title: String) = viewModelScope.launch { runCatching { graph.api.renamePlaylist(id, title) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun deletePlaylist(id: String) = viewModelScope.launch { runCatching { graph.api.deletePlaylist(id) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun addToPlaylist(track: Track, id: String) = viewModelScope.launch { runCatching { graph.api.changePlaylistTrack(id, track, true) }.onSuccess { _state.update { it.copy(message = "已加入歌单") }; loadLibrary() }.onFailure(::fail) }
    fun removeFromPlaylist(track: Track, id: String) = viewModelScope.launch {
        runCatching { graph.api.changePlaylistTrack(id, track, false) }
            .onSuccess {
                val tracks = _state.value.detail?.tracks.orEmpty().filterNot { item -> item.id == track.id }
                _state.update { state -> state.copy(message = "已从歌单移除", detail = state.detail?.copy(tracks = tracks)) }
            }
            .onFailure(::fail)
    }
    fun setQuality(value: String) = viewModelScope.launch { graph.settings.setQuality(value) }
    fun setHeadphoneWarning(value: Boolean) = viewModelScope.launch { graph.settings.setHeadphoneWarning(value) }
    fun setAutoOpenPlayer(value: Boolean) = viewModelScope.launch { graph.settings.setAutoOpenPlayer(value) }
    fun setPlayMode(value: String) = viewModelScope.launch { graph.settings.setPlayMode(value) }
    fun setLyricSize(value: String) = viewModelScope.launch { graph.settings.setLyricSize(value) }
    fun setLyricTranslation(value: Boolean) = viewModelScope.launch { graph.settings.setLyricTranslation(value) }
    fun setLyricOriginal(value: Boolean) = viewModelScope.launch { graph.settings.setLyricOriginal(value) }
    fun setLyricOffset(value: Long) = viewModelScope.launch { graph.settings.setLyricOffset(value) }
    fun setLyricAnimation(value: String) = viewModelScope.launch { graph.settings.setLyricAnimation(value) }
    fun setPureBlack(value: Boolean) = viewModelScope.launch { graph.settings.setPureBlack(value) }
    fun setLowPowerPlayer(value: Boolean) = viewModelScope.launch { graph.settings.setLowPowerPlayer(value) }
    fun setWifiOnlyDownload(value: Boolean) = viewModelScope.launch { graph.settings.setWifiOnlyDownload(value) }
    fun setDailyCount(value: Int) = viewModelScope.launch { graph.settings.setDailyCount(value) }
    fun addToQueue(track: Track) { if (_queue.value.none { it.id == track.id }) _queue.value = _queue.value + track; persistSnapshot(); _state.update { it.copy(message = "已加入播放列表") } }
    fun enqueueNext(track: Track) {
        val items = insertNext(_queue.value, _state.value.currentTrack?.id, track)
        _queue.value = items
        _queueIndex.value = _state.value.currentTrack?.let { playing -> items.indexOfFirst { it.id == playing.id } } ?: -1
        persistSnapshot()
        _state.update { it.copy(message = "已设为下一首") }
    }
    fun removeFromQueue(index: Int) {
        val items = _queue.value.toMutableList()
        if (index !in items.indices) return
        items.removeAt(index); _queue.value = items
        _queueIndex.value = _state.value.currentTrack?.let { playing -> items.indexOfFirst { it.id == playing.id } } ?: -1
        persistSnapshot()
    }
    fun clearQueue() { _queue.value = emptyList(); _queueIndex.value = -1; persistSnapshot() }
    fun removeQueueDuplicates() {
        val before = _queue.value.size
        _queue.value = _queue.value.distinctBy(Track::id)
        _queueIndex.value = _state.value.currentTrack?.let { playing -> _queue.value.indexOfFirst { it.id == playing.id } } ?: -1
        persistSnapshot(); _state.update { it.copy(message = "已移除 ${before - _queue.value.size} 首重复歌曲") }
    }
    fun reverseQueue() {
        _queue.value = _queue.value.reversed(); _queueReversed.value = !_queueReversed.value
        _queueIndex.value = _state.value.currentTrack?.let { playing -> _queue.value.indexOfFirst { it.id == playing.id } } ?: -1
        persistSnapshot()
    }
    fun moveQueue(index: Int, delta: Int) {
        val target = index + delta
        if (index !in _queue.value.indices || target !in _queue.value.indices) return
        val items = _queue.value.toMutableList(); val item = items.removeAt(index); items.add(target, item); _queue.value = items
        _queueIndex.value = _state.value.currentTrack?.let { playing -> items.indexOfFirst { it.id == playing.id } } ?: -1
        persistSnapshot()
    }
    fun playQueueItem(index: Int) { _queue.value.getOrNull(index)?.let { requestPlay(it, true) } }
    fun skipNext() = playAdjacent(1, false)
    fun skipPrevious() = playAdjacent(-1, false)
    private fun advanceAfterEnd() = playAdjacent(1, true)
    private fun playAdjacent(delta: Int, ended: Boolean) {
        if (_queue.value.isEmpty()) return
        val mode = playMode.value
        val random = if (mode == "shuffle" && _queue.value.size > 1) _queue.value.indices.filter { it != _queueIndex.value }.random() else -1
        val target = nextQueueIndex(_queue.value.size, _queueIndex.value, delta, mode, ended, random)
        if (target < 0) return
        playQueueItem(target)
    }
    fun saveQueueAsPlaylist(title: String) = viewModelScope.launch {
        runCatching {
            val playlist = graph.api.createPlaylist(title)
            _queue.value.forEach { graph.api.changePlaylistTrack(playlist.directoryId, it, true) }
        }.onSuccess { _state.update { it.copy(message = "播放列表已保存为歌单") }; loadLibrary() }.onFailure(::fail)
    }
    fun importPlaylistToQueue(collection: MusicCollection) = viewModelScope.launch {
        runCatching { graph.api.collection("playlist", collection) }.onSuccess { detail ->
            val before = _queue.value.size
            _queue.value = (_queue.value + detail.tracks).distinctBy(Track::id)
            _queueIndex.value = _state.value.currentTrack?.let { current -> _queue.value.indexOfFirst { it.id == current.id } } ?: -1
            persistSnapshot(); _state.update { it.copy(message = "已从${collection.title}添加 ${_queue.value.size - before} 首") }
        }.onFailure(::fail)
    }
    fun startSleepTimer(minutes: Int, finishCurrent: Boolean = false) { graph.playback.startSleepTimer(minutes, finishCurrent); viewModelScope.launch { graph.settings.setLastSleepMinutes(minutes) }; _state.update { it.copy(message = if (finishCurrent) "$minutes 分钟后播完当前歌曲关闭" else "将在 $minutes 分钟后停止播放") } }
    fun cancelSleepTimer() { graph.playback.cancelSleepTimer(); _state.update { it.copy(message = "已取消定时关闭") } }
    fun playbackPosition() = graph.playback.position()
    fun playbackDuration() = graph.playback.duration()
    fun isPlaying() = graph.playback.isPlaying()
    fun pausePlayback() { graph.playback.pause(); persistSnapshot() }
    fun resumePlayback() { if (graph.playback.duration() == 0L) _state.value.currentTrack?.let { requestPlay(it, true) } else graph.playback.resume() }
    fun seek(position: Long) = graph.playback.seek(position)
    fun adjustVolume(direction: Int) = graph.playback.adjustVolume(direction)
    fun savePlaybackState() = persistSnapshot()
    private fun persistSnapshot(position: Long = graph.playback.position()) = viewModelScope.launch {
        graph.settings.setPlaybackSnapshot(json.encodeToString(PlaybackSnapshot(_state.value.currentTrack, _queue.value, position, _queueReversed.value)))
    }
    private fun preferredQuality(track: Track) = if (quality.value == "320" && "320" in track.qualities) "320" else "128"
    override fun onCleared() { graph.playback.onEnded = null; graph.playback.onError = null; super.onCleared() }
}
