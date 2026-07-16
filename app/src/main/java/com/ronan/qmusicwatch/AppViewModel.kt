package com.ronan.qmusicwatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronan.qmusicwatch.data.RecentEntity
import com.ronan.qmusicwatch.data.mergeRecent
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.download.cachedArtworkFile
import com.ronan.qmusicwatch.download.cachedLyricsFile
import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.playback.PlaybackErrorEvent
import com.ronan.qmusicwatch.playback.classifyPlaybackFailure
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

data class AppUiState(
    val loading: Boolean = false, val message: String? = null, val home: HomeData? = null,
    val library: LibraryData? = null, val recent: List<Track> = emptyList(), val searchTracks: List<Track> = emptyList(),
    val searchCollections: List<MusicCollection> = emptyList(), val searchType: String = "track",
    val searchQuery: String = "", val searchCursor: String? = null, val searchLoading: Boolean = false,
    val qrStatus: String = "", val currentTrack: Track? = null,
    val lyrics: List<LyricLine> = emptyList(), val pendingSpeakerTrack: Track? = null,
    val detail: CollectionDetail? = null, val detailDirectoryId: String? = null, val playEvent: Long = 0,
    val diagnostic: String? = null, val profile: UserProfile? = null, val offlineSnapshot: Boolean = false,
    val releaseInfo: ReleaseInfo? = null, val updateChecking: Boolean = false,
    val queueImportTitle: String = "", val queueImportTracks: List<Track> = emptyList(), val queueImportLoading: Boolean = false,
)

internal fun insertNext(queue: List<Track>, currentId: String?, track: Track): List<Track> {
    val items = queue.filterNot { it.id == track.id }.toMutableList()
    val current = items.indexOfFirst { it.id == currentId }
    items.add((current + 1).coerceIn(0, items.size), track)
    return items
}

internal fun queueDropIndex(visibleQueueIndices: List<Int>, visiblePosition: Int, dragPx: Float, itemHeightPx: Int): Int? {
    if (visiblePosition !in visibleQueueIndices.indices || itemHeightPx <= 0) return null
    val target = (visiblePosition + (dragPx / itemHeightPx).roundToInt()).coerceIn(visibleQueueIndices.indices)
    return visibleQueueIndices[target]
}

internal fun queueEdgeScrollDirection(fingerY: Float, viewportHeight: Int, edgePx: Float): Int = when {
    viewportHeight <= 0 || edgePx <= 0 -> 0
    fingerY < edgePx -> -1
    fingerY > viewportHeight - edgePx -> 1
    else -> 0
}

internal fun queueReorderStep(dragPx: Float, itemHeightPx: Int): Int = when {
    itemHeightPx <= 0 -> 0
    dragPx >= itemHeightPx / 2f -> 1
    dragPx <= -itemHeightPx / 2f -> -1
    else -> 0
}

internal fun moveQueuePreview(queue: List<Track>, from: Int, to: Int): List<Track> {
    if (from !in queue.indices || to !in queue.indices || from == to) return queue
    return queue.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun profileCacheNeedsRefresh(cache: CachedUserProfile?, accountId: String?, now: Long): Boolean =
    cache == null || cache.accountId != accountId || cache.profile.isVip == null || cache.profile.vipExpireAt?.let { it * 1000 <= now } == true

internal fun upsertAccountSnapshot(cache: CachedAccountSnapshots, value: CachedAccountSnapshot): CachedAccountSnapshots =
    CachedAccountSnapshots((listOf(value) + cache.items.filterNot { it.accountId == value.accountId }).take(4))

internal fun mergeSelectedQueue(queue: List<Track>, source: List<Track>, selectedIds: Set<String>): List<Track> =
    (queue + source.filter { it.id in selectedIds }).distinctBy(Track::id)

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
    private var lastStreamUrl = ""
    private var lastStreamExpiresAt = 0L
    private var lastStreamQuality = "128"
    private var retryingTrackId: String? = null
    private var searchJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val profileCacheReady = CompletableDeferred<Unit>()
    private val snapshotMutex = Mutex()
    private var currentSession = graph.vault.load()
    val signedIn get() = currentSession != null
    val accountId get() = currentSession?.accountId
    val loginProvider get() = currentSession?.provider ?: "qq"

    init {
        graph.playback.onEnded = ::advanceAfterEnd
        graph.playback.onError = ::handlePlaybackError
        viewModelScope.launch {
            runCatching { json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first()) }.getOrNull()?.takeIf { it.belongsToAccount(accountId) }?.let { snapshot ->
                _queue.value = snapshot.queue.distinctBy(Track::id)
                _queueReversed.value = snapshot.queueReversed
                restoredPosition = snapshot.positionMs
                lastStreamUrl = snapshot.streamUrl
                lastStreamExpiresAt = snapshot.streamExpiresAt
                lastStreamQuality = snapshot.quality
                snapshot.track?.let { track ->
                    _state.update { it.copy(currentTrack = track) }
                    _queueIndex.value = _queue.value.indexOfFirst { item -> item.id == track.id }
                }
            }
        }
        viewModelScope.launch {
            var refresh = false
            try {
                if (signedIn) {
                    val cached = runCatching { json.decodeFromString<CachedUserProfile>(graph.settings.profileCache.first()) }.getOrNull()
                    cached?.takeIf { it.accountId == accountId }?.let { _state.update { state -> state.copy(profile = it.profile) } }
                    refresh = profileCacheNeedsRefresh(cached, accountId, System.currentTimeMillis())
                }
            } finally { profileCacheReady.complete(Unit) }
            if (refresh) loadProfile(force = true)
        }
        viewModelScope.launch { while (isActive) { delay(10_000); if (_state.value.currentTrack != null) persistSnapshot() } }
        loadHome()
    }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun fail(error: Throwable) {
        AppLog.write("ERROR", generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}:${it.message.orEmpty()}" })
        _state.update { it.copy(loading = false, searchLoading = false, message = error.message ?: "操作失败") }
    }

    private fun failPlayback(error: Throwable) {
        val failure = classifyPlaybackFailure(error)
        AppLog.write("PLAYBACK", "type=${failure.type} ${error.javaClass.simpleName}:${error.message.orEmpty()}")
        _state.update { it.copy(loading = false, message = failure.message) }
    }

    private fun handlePlaybackError(event: PlaybackErrorEvent) {
        val track = _state.value.currentTrack
        val failure = classifyPlaybackFailure(event.error)
        if (track == null || track.id != event.mediaId || event.isLocalFile || !failure.retryable || retryingTrackId == track.id) {
            _state.update { it.copy(message = failure.message) }
            return
        }
        retryingTrackId = track.id
        viewModelScope.launch {
            _state.update { it.copy(message = "播放连接中断，正在自动恢复…") }
            runCatching {
                val stream = graph.api.stream(track, preferredQuality(track))
                if (_state.value.currentTrack?.id != track.id) throw CancellationException("歌曲已切换")
                graph.playback.play(track.id, stream.url, track.title, track.artists.joinToString(" / "), track.artworkUrl)
                graph.playback.seek(event.positionMs)
                lastStreamUrl = stream.url
                lastStreamExpiresAt = stream.expiresAt
                lastStreamQuality = stream.quality
                persistSnapshot(event.positionMs)
            }.onSuccess {
                _state.update { it.copy(message = "已从 ${lyricTimeForMessage(event.positionMs)} 自动恢复播放") }
                delay(10_000)
                if (_state.value.currentTrack?.id == track.id) retryingTrackId = null
            }.onFailure { error -> retryingTrackId = null; if (error !is CancellationException) failPlayback(error) }
        }
    }

    private fun lyricTimeForMessage(positionMs: Long): String =
        "${positionMs.coerceAtLeast(0) / 60_000}:${((positionMs.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"

    fun loadHome() = viewModelScope.launch {
        restoreAccountSnapshot()
        _state.update { it.copy(loading = it.home == null) }
        runCatching { graph.api.home() }.onSuccess { home ->
            _state.update { s -> s.copy(loading = false, home = home, offlineSnapshot = false) }
            cacheAccountSnapshot(home = home)
        }.onFailure { _state.update { s -> s.copy(loading = false, offlineSnapshot = s.home != null) } }
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
            loadHome(); loadProfile(force = true)
        }.onFailure { error ->
            _state.update { s -> s.copy(qrStatus = "登录失败，请返回刷新二维码") }
            fail(error)
        }
    }
    fun logout() {
        graph.playback.pause(); graph.vault.clear(); currentSession = null
        _queue.value = emptyList(); _queueIndex.value = -1; _queueReversed.value = false
        restoredPosition = 0; lastStreamUrl = ""; lastStreamExpiresAt = 0; retryingTrackId = null
        viewModelScope.launch { graph.settings.setPlaybackSnapshot("") }
        android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush()
        _state.update { it.copy(library = null, recent = emptyList(), profile = null, currentTrack = null, lyrics = emptyList(), message = "已退出，离线文件已保留并锁定") }
    }
    fun loadLibrary() = viewModelScope.launch {
        if (!signedIn) return@launch fail(IllegalStateException("请先登录"))
        restoreAccountSnapshot()
        runCatching { graph.api.library() }.onSuccess { value ->
            _state.update { it.copy(library = value, offlineSnapshot = false) }
            cacheAccountSnapshot(library = value)
        }.onFailure { error ->
            if (_state.value.library != null) _state.update { it.copy(offlineSnapshot = true, message = "网络不可用，正在显示离线收藏与歌单") }
            else fail(error)
        }
    }

    private suspend fun restoreAccountSnapshot() {
        val owner = accountId ?: "guest"
        val cached = runCatching { json.decodeFromString<CachedAccountSnapshots>(graph.settings.accountSnapshots.first()) }.getOrNull()
            ?.items?.firstOrNull { it.accountId == owner } ?: return
        _state.update { state -> state.copy(
            home = state.home ?: cached.home,
            library = state.library ?: cached.library,
            offlineSnapshot = true,
        ) }
    }

    private suspend fun cacheAccountSnapshot(home: HomeData? = null, library: LibraryData? = null) = snapshotMutex.withLock {
        val owner = accountId ?: "guest"
        val cache = runCatching { json.decodeFromString<CachedAccountSnapshots>(graph.settings.accountSnapshots.first()) }.getOrDefault(CachedAccountSnapshots())
        val old = cache.items.firstOrNull { it.accountId == owner }
        val updated = CachedAccountSnapshot(owner, home ?: old?.home, library ?: old?.library, System.currentTimeMillis())
        graph.settings.setAccountSnapshots(json.encodeToString(upsertAccountSnapshot(cache, updated)))
    }
    fun loadProfile(force: Boolean = false) = viewModelScope.launch {
        profileCacheReady.await()
        if (!signedIn || (!force && _state.value.profile != null)) return@launch
        runCatching { graph.api.profile() }.onSuccess { profile ->
            _state.update { it.copy(profile = profile) }
            graph.settings.setProfileCache(json.encodeToString(CachedUserProfile(accountId.orEmpty(), profile, System.currentTimeMillis())))
        }
            .onFailure { AppLog.write("PROFILE", "${it.javaClass.simpleName}:${it.message.orEmpty()}") }
    }
    fun diagnose() = viewModelScope.launch {
        _state.update { it.copy(message = "正在检查登录、歌单和播放地址…") }
        runCatching { graph.api.diagnose() }
            .onSuccess { result -> _state.update { it.copy(message = result, diagnostic = result) }; loadProfile(force = true) }
            .onFailure { error -> _state.update { it.copy(diagnostic = "诊断失败：${error.message ?: "未知错误"}") }; fail(error) }
    }
    fun loadRecent() = viewModelScope.launch {
        val owner = accountId ?: return@launch _state.update { it.copy(recent = emptyList()) }
        val local = graph.db.recent().all(owner).map { Track(it.trackId, it.title, it.artists.split(" / "), it.album, it.artworkUrl, playedAt = it.playedAt) }
        val cloud = if (signedIn) runCatching { graph.api.recent() }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(recent = mergeRecent(local, cloud)) }
    }
    fun search(query: String, type: String, loadMore: Boolean = false) {
        if (query.isBlank()) return
        val append = loadMore && _state.value.searchQuery == query && _state.value.searchType == type
        val cursor = _state.value.searchCursor.takeIf { append } ?: if (loadMore) return else null
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { state -> state.copy(searchLoading = true, searchType = type, searchQuery = query, searchCursor = if (append) state.searchCursor else null, searchTracks = if (!append && type == "track") emptyList() else state.searchTracks, searchCollections = if (!append && type != "track") emptyList() else state.searchCollections) }
            if (!append) graph.settings.addSearchHistory(query)
            try {
                val (tracks, collections, next) = if (type == "track") graph.api.searchTracks(query, cursor).let { Triple(it.items, emptyList<MusicCollection>(), it.nextCursor) }
                else graph.api.searchCollections(type, query, cursor).let { Triple(emptyList<Track>(), it.items, it.nextCursor) }
                _state.update { state -> state.copy(searchTracks = (if (append) state.searchTracks + tracks else tracks).distinctBy(Track::id), searchCollections = (if (append) state.searchCollections + collections else collections).distinctBy(MusicCollection::id), searchCursor = next, searchLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { fail(error) }
        }
    }
    fun clearSearchHistory() = viewModelScope.launch { graph.settings.clearSearchHistory() }
    fun checkForUpdate() = viewModelScope.launch {
        _state.update { it.copy(updateChecking = true) }
        runCatching { graph.api.latestRelease(BuildConfig.VERSION_NAME) }
            .onSuccess { release -> _state.update { it.copy(updateChecking = false, releaseInfo = release, message = if (release.newer) "发现新版本 ${release.tag}" else "当前已是最新版本") } }
            .onFailure { error -> _state.update { it.copy(updateChecking = false) }; fail(error) }
    }
    fun requestPlay(track: Track, allowSpeaker: Boolean = false, sourceQueue: List<Track>? = null) = viewModelScope.launch {
        if (!track.playable && !track.requiresVip) return@launch fail(IllegalStateException("这首歌曲当前不可播放"))
        if (headphoneWarning.value && !allowSpeaker && !com.ronan.qmusicwatch.playback.hasPrivateAudioOutput(getApplication())) {
            pendingQueue = sourceQueue
            _state.update { it.copy(pendingSpeakerTrack = track) }; return@launch
        }
        val owner = accountId ?: return@launch fail(IllegalStateException("请先登录后播放"))
        var issuedUrl = ""
        var issuedExpiresAt = 0L
        var issuedQuality = preferredQuality(track)
        var resolvedTrack = track
        runCatching {
            val local = graph.db.downloads().find(track.id, owner)?.takeIf { it.status == "complete" && File(it.filePath).exists() }
            val stream = if (local == null) graph.api.stream(track, preferredQuality(track)) else null
            val uri = local?.let { android.net.Uri.fromFile(File(it.filePath)).toString() } ?: stream!!.url
            local?.let { item -> cachedArtworkFile(item.filePath).takeIf(File::exists)?.let { resolvedTrack = track.copy(artworkUrl = android.net.Uri.fromFile(it).toString()) } }
            issuedUrl = uri
            issuedExpiresAt = stream?.expiresAt ?: Long.MAX_VALUE
            issuedQuality = stream?.quality ?: preferredQuality(track)
            graph.playback.play(track.id, uri, track.title, track.artists.joinToString(" / "), resolvedTrack.artworkUrl)
            if (restoredPosition > 0 && _state.value.currentTrack?.id == track.id) graph.playback.seek(restoredPosition)
            graph.db.recent().upsert(RecentEntity(track.id, owner, track.title, track.artists.joinToString(" / "), track.album, track.artworkUrl, System.currentTimeMillis()))
            runCatching { loadLyrics(track, local?.filePath) }.onFailure { AppLog.write("LYRICS", "${track.id} ${it.javaClass.simpleName}:${it.message.orEmpty()}") }.getOrDefault(emptyList())
        }.onSuccess { parsedLyrics ->
            sourceQueue?.takeIf(List<Track>::isNotEmpty)?.let { source ->
                _queue.value = source.distinctBy(Track::id)
                _queueReversed.value = false
            }
            if (_queue.value.none { it.id == track.id }) _queue.value = _queue.value + track
            _queueIndex.value = _queue.value.indexOfFirst { it.id == track.id }
            pendingQueue = null
            restoredPosition = 0
            retryingTrackId = null
            lastStreamUrl = issuedUrl
            lastStreamExpiresAt = issuedExpiresAt
            lastStreamQuality = issuedQuality
            _state.update { it.copy(currentTrack = resolvedTrack, lyrics = parsedLyrics, pendingSpeakerTrack = null, playEvent = System.nanoTime()) }
            persistSnapshot()
        }.onFailure(::failPlayback)
    }
    fun continueOnSpeaker() { _state.value.pendingSpeakerTrack?.let { requestPlay(it, true, pendingQueue) } }
    fun dismissSpeakerPrompt() = _state.update { it.copy(pendingSpeakerTrack = null) }
    private suspend fun loadLyrics(track: Track, localAudioPath: String?): List<LyricLine> {
        var data = localAudioPath?.let { cachedLyricsFile(it).takeIf(File::exists)?.let { file -> runCatching { json.decodeFromString<LyricsData>(file.readText()) }.getOrNull() } }
        if (data == null) {
            data = graph.api.lyrics(track.id)
            localAudioPath?.let { cachedLyricsFile(it).writeText(json.encodeToString(data)) }
        }
        return LrcParser.parse(data.original, data.translation, data.wordSync)
    }
    fun reloadLyrics() = viewModelScope.launch {
        val track = _state.value.currentTrack ?: return@launch
        val owner = accountId ?: return@launch fail(IllegalStateException("请先登录"))
        val local = graph.db.downloads().find(track.id, owner)?.takeIf { it.status == "complete" && File(it.filePath).exists() }
        _state.update { it.copy(message = "正在重新加载歌词…") }
        runCatching { loadLyrics(track, local?.filePath) }
            .onSuccess { lines -> _state.update { it.copy(lyrics = lines, message = if (lines.isEmpty()) "这首歌暂无歌词" else "歌词已重新加载") } }
            .onFailure(::fail)
    }
    fun cache(track: Track, groupName: String = "单曲缓存") {
        val owner = accountId ?: return fail(IllegalStateException("请先登录"))
        runCatching { graph.downloads.enqueue(track, owner, preferredQuality(track), wifiOnlyDownload.value, groupName) }
            .onSuccess { _state.update { s -> s.copy(message = if (wifiOnlyDownload.value) "已加入缓存，等待 Wi-Fi" else "已加入离线缓存") } }
            .onFailure(::fail)
    }
    fun cacheAll(tracks: List<Track>, groupName: String = "播放列表缓存") = tracks.forEach { cache(it, groupName) }
    fun resumeDownload(item: com.ronan.qmusicwatch.data.DownloadEntity) = cache(Track(item.trackId, item.title, item.artists.split(" / "), artworkUrl = item.artworkUrl, qualities = listOf("128", "320")), item.groupName)
    fun pauseDownload(id: String) = viewModelScope.launch {
        val owner = accountId ?: return@launch
        runCatching { graph.downloads.pause(id, owner) }.onFailure(::fail)
    }
    fun deleteDownload(id: String, owner: String) = viewModelScope.launch {
        runCatching { graph.downloads.delete(id, owner) }.onFailure(::fail)
    }
    fun deleteInvalidDownloads() = viewModelScope.launch {
        val owner = accountId ?: return@launch
        runCatching { graph.downloads.deleteInvalid(owner) }
            .onSuccess { count -> _state.update { it.copy(message = if (count == 0) "没有失效缓存" else "已删除 $count 个失效缓存") } }
            .onFailure(::fail)
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
    fun replaceQueueOrder(items: List<Track>) {
        if (items.map(Track::id).sorted() != _queue.value.map(Track::id).sorted()) return
        if (items.map(Track::id) == _queue.value.map(Track::id)) return
        _queue.value = items
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
    fun loadQueueImportLiked() = _state.update { it.copy(queueImportTitle = "我喜欢", queueImportTracks = it.library?.liked.orEmpty(), queueImportLoading = false) }
    fun loadQueueImportPlaylist(collection: MusicCollection) = viewModelScope.launch {
        _state.update { it.copy(queueImportTitle = collection.title, queueImportTracks = emptyList(), queueImportLoading = true) }
        runCatching { graph.api.collection("playlist", collection) }
            .onSuccess { detail -> _state.update { it.copy(queueImportTitle = detail.title, queueImportTracks = detail.tracks, queueImportLoading = false) } }
            .onFailure { error -> clearQueueImport(); fail(error) }
    }
    fun clearQueueImport() = _state.update { it.copy(queueImportTitle = "", queueImportTracks = emptyList(), queueImportLoading = false) }
    fun addSelectedQueueTracks(ids: Set<String>) {
        val before = _queue.value.size
        _queue.value = mergeSelectedQueue(_queue.value, _state.value.queueImportTracks, ids)
        _queueIndex.value = _state.value.currentTrack?.let { current -> _queue.value.indexOfFirst { it.id == current.id } } ?: -1
        val added = _queue.value.size - before
        persistSnapshot(); clearQueueImport(); _state.update { it.copy(message = "已加入 $added 首歌曲") }
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
        val owner = accountId ?: return@launch
        graph.settings.setPlaybackSnapshot(json.encodeToString(PlaybackSnapshot(
            _state.value.currentTrack, _queue.value, position, _queueReversed.value,
            lastStreamUrl, lastStreamExpiresAt, lastStreamQuality, owner,
        )))
    }
    private fun preferredQuality(track: Track) = if (quality.value == "320" && "320" in track.qualities) "320" else "128"
    override fun onCleared() { graph.playback.onEnded = null; graph.playback.onError = null; super.onCleared() }
}
