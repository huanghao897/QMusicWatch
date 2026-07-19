package com.ronan.qmusicwatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronan.qmusicwatch.data.RecentEntity
import com.ronan.qmusicwatch.data.mergeRecent
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.data.redactDiagnosticMessage
import com.ronan.qmusicwatch.download.cachedArtworkFile
import com.ronan.qmusicwatch.download.cachedLyricsFile
import com.ronan.qmusicwatch.lyrics.LrcParser
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.model.*
import com.ronan.qmusicwatch.network.normalizeLibraryData
import com.ronan.qmusicwatch.network.*
import com.ronan.qmusicwatch.playback.PlaybackErrorEvent
import com.ronan.qmusicwatch.playback.classifyPlaybackFailure
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val library: LibraryData? = null, val recent: List<Track> = emptyList(), val recentLoaded: Boolean = false,
    val searchTracks: List<Track> = emptyList(),
    val searchCollections: List<MusicCollection> = emptyList(), val searchType: String = "track",
    val searchQuery: String = "", val searchCursor: String? = null, val searchLoading: Boolean = false,
    val qrStatus: String = "", val qrImageBase64: String = "", val qrMimeType: String = "", val qrExpiresAt: Long = 0,
    val currentTrack: Track? = null, val activeStreamQuality: String = QUALITY_STANDARD,
    val lyrics: List<LyricLine> = emptyList(), val pendingSpeakerTrack: Track? = null,
    val detail: CollectionDetail? = null, val detailDirectoryId: String? = null, val playEvent: Long = 0,
    val diagnostic: String? = null, val profile: UserProfile? = null, val profileLoaded: Boolean = false,
    val profileError: String? = null, val offlineSnapshot: Boolean = false,
    val releaseInfo: ReleaseInfo? = null, val updateChecking: Boolean = false,
    val controlConfig: RemoteFeatureConfig = RemoteFeatureConfig(),
    val announcements: List<ControlAnnouncement> = emptyList(),
    val controlFetchedAt: Long = 0, val controlRefreshing: Boolean = false, val controlError: String? = null,
    val updateState: UpdateUiState = UpdateUiState.Idle,
    val diagnosticUploadState: DiagnosticUploadState = DiagnosticUploadState.Idle,
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

internal fun profileCacheNeedsRefresh(cache: CachedUserProfile?, accountId: String?, now: Long): Boolean {
    val profile = cache?.profile?.let(::normalizeUserProfile)
    val expiry = normalizeEpochSeconds(profile?.vipExpireAt)
    val expiredMembership = profile?.isVip != false && expiry?.let { it <= now / 1_000L } == true
    return cache == null || cache.accountId != accountId || expiredMembership
}

internal fun upsertAccountSnapshot(cache: CachedAccountSnapshots, value: CachedAccountSnapshot): CachedAccountSnapshots =
    CachedAccountSnapshots((listOf(value) + cache.items.filterNot { it.accountId == value.accountId }).take(4))

internal fun mergeSelectedQueue(queue: List<Track>, source: List<Track>, selectedIds: Set<String>): List<Track> =
    (queue + source.filter { it.id in selectedIds }).distinctBy(Track::id)

internal fun qualityFallbackMessage(preferred: String, resolved: String): String? =
    "${qualityLabel(preferred)}不可用，已自动使用${qualityLabel(resolved)}".takeIf {
        normalizeQualityId(preferred) != normalizeQualityId(resolved)
    }

internal fun qualityFallbackMessage(preferred: String, resolved: String, track: Track, profile: UserProfile?): String? {
    if (normalizeQualityId(preferred) == normalizeQualityId(resolved)) return null
    val decision = resolveQuality(preferred, track, profile)
    val reason = decision.reason.ifBlank { "当前歌曲或账号未提供所选音质" }
    return "${qualityLabel(preferred)}不可用：$reason，已自动使用${qualityLabel(resolved)}"
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
    val quality = graph.settings.quality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QUALITY_STANDARD)
    /** Account-level options for the compact watch quality picker. */
    val qualityEntitlements = state.map { profileQualityOptions(it.profile) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, profileQualityOptions(null))
    val headphoneWarning = graph.settings.headphoneWarning.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoOpenPlayer = graph.settings.autoOpenPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val playMode = graph.settings.playMode.stateIn(viewModelScope, SharingStarted.Eagerly, "sequential")
    val lyricSize = graph.settings.lyricSize.stateIn(viewModelScope, SharingStarted.Eagerly, "normal")
    val lyricTranslation = graph.settings.lyricTranslation.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lyricOriginal = graph.settings.lyricOriginal.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lyricOffset = graph.settings.lyricOffset.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val lyricAnimation = graph.settings.lyricAnimation.stateIn(viewModelScope, SharingStarted.Eagerly, "soft")
    val lyricAlignment = graph.settings.lyricAlignment.stateIn(viewModelScope, SharingStarted.Eagerly, "left")
    val pureBlack = graph.settings.pureBlack.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lowPowerPlayer = graph.settings.lowPowerPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val wifiOnlyDownload = graph.settings.wifiOnlyDownload.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastSleepMinutes = graph.settings.lastSleepMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val dailyCount = graph.settings.dailyCount.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val searchHistory = graph.settings.searchHistory.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val seenAnnouncements = graph.settings.seenAnnouncements.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
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
    private var lastStreamQuality = QUALITY_STANDARD
    private var retryingTrackId: String? = null
    private var recoveryJob: Job? = null
    private var playJob: Job? = null
    private var qualitySwitchJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var queueImportJob: Job? = null
    private var updateJob: Job? = null
    private var qrLoginJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val profileCacheReady = CompletableDeferred<Unit>()
    private val snapshotMutex = Mutex()
    private var currentSession = graph.vault.load()
    private var sessionGeneration = 0L
    val signedIn get() = currentSession != null
    val accountId get() = currentSession?.accountId
    val loginProvider get() = currentSession?.provider ?: "qq"

    init {
        graph.playback.onError = ::handlePlaybackError
        graph.playback.onMediaItemChanged = ::handleMediaItemChanged
        viewModelScope.launch {
            val generation = sessionGeneration
            val owner = accountId
            runCatching { json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first()) }.getOrNull()?.takeIf { it.belongsToAccount(owner) && generation == sessionGeneration }?.let { snapshot ->
                _queue.value = snapshot.queue.distinctBy(Track::id)
                _queueReversed.value = snapshot.queueReversed
                restoredPosition = snapshot.positionMs
                lastStreamUrl = snapshot.streamUrl
                lastStreamExpiresAt = snapshot.streamExpiresAt
                lastStreamQuality = reportedQualityId(snapshot.quality)
                snapshot.track?.let { track ->
                    _state.update { it.copy(currentTrack = track, activeStreamQuality = lastStreamQuality) }
                    _queueIndex.value = _queue.value.indexOfFirst { item -> item.id == track.id }
                }
            }
        }
        viewModelScope.launch {
            val generation = sessionGeneration
            var refresh = false
            try {
                if (signedIn) {
                    val cached = runCatching { json.decodeFromString<CachedUserProfile>(graph.settings.profileCache.first()) }
                        .getOrNull()?.let(::normalizeCachedUserProfile)
                    cached?.takeIf { it.accountId == accountId && generation == sessionGeneration }?.let { _state.update { state -> state.copy(profile = it.profile, profileLoaded = true, profileError = null) } }
                    refresh = profileCacheNeedsRefresh(cached, accountId, System.currentTimeMillis())
                }
            } finally { profileCacheReady.complete(Unit) }
            if (refresh && generation == sessionGeneration) loadProfile(force = true)
        }
        viewModelScope.launch { while (isActive) { delay(10_000); if (_state.value.currentTrack != null) persistSnapshot() } }
        viewModelScope.launch { restoreControlPlaneAndRefresh() }
        viewModelScope.launch { restorePendingUpdate() }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                val current = _state.value
                val cache = CachedControlPlane(current.controlConfig, current.announcements, current.controlFetchedAt)
                if (!current.controlRefreshing && !controlCacheIsFresh(cache, System.currentTimeMillis())) {
                    refreshControlPlane(showStatus = false)
                }
            }
        }
        loadHome()
        if (signedIn) { loadLibrary(); loadRecent() }
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
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            _state.update { it.copy(message = "播放连接中断，正在自动恢复…") }
            runCatching {
                val stream = graph.api.stream(track, preferredQuality(track))
                if (_state.value.currentTrack?.id != track.id) throw CancellationException("歌曲已切换")
                graph.playback.play(track.id, stream.url, track.title, track.artists.joinToString(" / "), track.artworkUrl)
                graph.playback.seek(event.positionMs)
                lastStreamUrl = stream.url
                lastStreamExpiresAt = stream.expiresAt
                lastStreamQuality = stream.quality
                _state.update { it.copy(activeStreamQuality = stream.quality) }
                persistSnapshot(event.positionMs)
            }.onSuccess {
                val qualityNote = qualityFallbackMessage(quality.value, lastStreamQuality, track, _state.value.profile)
                _state.update { it.copy(message = "已从 ${lyricTimeForMessage(event.positionMs)} 自动恢复播放" + qualityNote?.let { note -> " · $note" }.orEmpty()) }
                delay(10_000)
                if (_state.value.currentTrack?.id == track.id) retryingTrackId = null
            }.onFailure { error -> retryingTrackId = null; if (error !is CancellationException) failPlayback(error) }
        }
    }

    private fun handleMediaItemChanged(mediaId: String, uri: String) {
        if (mediaId.isBlank() || _state.value.currentTrack?.id == mediaId || playJob?.isActive == true) return
        qualitySwitchJob?.cancel(); qualitySwitchJob = null
        recoveryJob?.cancel(); recoveryJob = null; retryingTrackId = null
        viewModelScope.launch {
            val snapshot = runCatching { json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first()) }.getOrNull()
            val snapshotQueue = snapshot?.takeIf { it.belongsToAccount(accountId) }?.queue.orEmpty().distinctBy(Track::id)
            val track = _queue.value.firstOrNull { it.id == mediaId }
                ?: snapshot?.track?.takeIf { it.id == mediaId }
                ?: snapshotQueue.firstOrNull { it.id == mediaId }
                ?: return@launch
            if (graph.playback.currentMediaId() != mediaId) return@launch
            if (snapshotQueue.isNotEmpty()) _queue.value = snapshotQueue
            _queueIndex.value = _queue.value.indexOfFirst { it.id == mediaId }
            lastStreamUrl = snapshot?.streamUrl?.takeIf { snapshot.track?.id == mediaId }.orEmpty().ifBlank { uri }
            lastStreamExpiresAt = snapshot?.streamExpiresAt?.takeIf { snapshot.track?.id == mediaId } ?: if (uri.startsWith("file:")) Long.MAX_VALUE else 0
            lastStreamQuality = reportedQualityId(snapshot?.quality?.takeIf { snapshot.track?.id == mediaId } ?: preferredQuality(track))
            _state.update { it.copy(currentTrack = track, activeStreamQuality = lastStreamQuality, lyrics = emptyList(), message = "已通过耳机或系统媒体控制切换歌曲") }
            val localPath = android.net.Uri.parse(uri).takeIf { it.scheme == "file" }?.path
            val lines = runCatching { loadLyrics(track, localPath) }
                .onFailure { AppLog.write("LYRICS", "media key ${it.javaClass.simpleName}:${it.message.orEmpty()}") }
                .getOrDefault(emptyList())
            if (graph.playback.currentMediaId() == mediaId) _state.update { it.copy(lyrics = lines) }
        }
    }

    private fun lyricTimeForMessage(positionMs: Long): String =
        "${positionMs.coerceAtLeast(0) / 60_000}:${((positionMs.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"

    fun loadHome() = viewModelScope.launch {
        val generation = sessionGeneration
        restoreAccountSnapshot()
        if (generation != sessionGeneration) return@launch
        _state.update { it.copy(loading = it.home == null) }
        runCatching { graph.api.home() }.onSuccess { home ->
            if (generation != sessionGeneration) return@onSuccess
            _state.update { s -> s.copy(loading = false, home = home, offlineSnapshot = false) }
            cacheAccountSnapshot(home = home)
        }.onFailure { if (generation == sessionGeneration) _state.update { s -> s.copy(loading = false, offlineSnapshot = s.home != null) } }
    }
    fun startQrLogin(provider: String) {
        qrLoginJob?.cancel()
        if (provider !in setOf("qq", "wechat")) {
            _state.update { it.copy(qrStatus = "不支持的登录方式", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0) }
            return
        }
        if (!featureEnabled("qrLogin")) {
            _state.update { it.copy(qrStatus = featureMessage("qrLogin").ifBlank { "扫码登录暂时维护" }, qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0) }
            return
        }
        qrLoginJob = viewModelScope.launch {
            _state.update { it.copy(qrStatus = "正在生成二维码…", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0) }
            try {
                val session = graph.controlPlane.createQrLogin(provider)
                _state.update { it.copy(
                    qrStatus = "请使用手机扫描二维码",
                    qrImageBase64 = session.imageBase64,
                    qrMimeType = session.mimeType,
                    qrExpiresAt = session.expiresAt,
                ) }
                while (isActive) {
                    delay(session.pollAfterMs.coerceIn(1_000, 30_000))
                    val result = graph.controlPlane.pollQrLogin(session.id, provider)
                    AppLog.write("LOGIN", "server_qr provider=$provider status=${result.status}")
                    when (result.status) {
                        "waiting" -> _state.update { it.copy(qrStatus = "请使用手机扫描二维码") }
                        "scanned" -> _state.update { it.copy(qrStatus = "已扫码，请在手机确认") }
                        "complete" -> {
                            _state.update { it.copy(qrStatus = "已确认，正在完成登录…") }
                            completeServerLogin(provider, result.cookie)
                            return@launch
                        }
                        "expired" -> {
                            _state.update { it.copy(qrStatus = "二维码已过期，点刷新重试") }
                            return@launch
                        }
                        "rejected" -> {
                            _state.update { it.copy(qrStatus = "已取消登录，点刷新重试") }
                            return@launch
                        }
                        else -> {
                            _state.update { it.copy(qrStatus = "登录服务暂时不可用，点刷新重试") }
                            return@launch
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.write("LOGIN", "server_qr ${error.javaClass.simpleName}:${error.message.orEmpty()}")
                _state.update { it.copy(qrStatus = "二维码加载失败，点刷新重试", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0) }
            }
        }
    }

    fun cancelQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        _state.update { it.copy(qrStatus = "", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0) }
    }

    private suspend fun completeServerLogin(provider: String, cookie: String) {
        runCatching {
            val account = com.ronan.qmusicwatch.login.MusicCookie.accountId(cookie)
                ?: error("登录响应缺少账号标识")
            SessionTokens(accountId = account, provider = com.ronan.qmusicwatch.login.MusicCookie.provider(cookie, provider), upstreamCookie = cookie)
        }.onSuccess { session ->
            runCatching { graph.downloads.pauseAll() }.onFailure { AppLog.write("DOWNLOAD", "account switch ${it.javaClass.simpleName}:${it.message.orEmpty()}") }
            playJob?.cancel(); playJob = null
            recoveryJob?.cancel(); recoveryJob = null; retryingTrackId = null
            graph.playback.stopAndClear()
            pendingQueue = null
            _queue.value = emptyList(); _queueIndex.value = -1; _queueReversed.value = false
            restoredPosition = 0; lastStreamUrl = ""; lastStreamExpiresAt = 0
            graph.vault.save(session)
            currentSession = session
            sessionGeneration++
            searchJob?.cancel(); searchJob = null
            detailJob?.cancel(); detailJob = null; queueImportJob?.cancel(); queueImportJob = null
            _state.update { s -> s.copy(
                home = null, library = null, recent = emptyList(), recentLoaded = false, profile = null, profileLoaded = false, profileError = null,
                detail = null, detailDirectoryId = null, offlineSnapshot = false,
                pendingSpeakerTrack = null,
                queueImportTitle = "", queueImportTracks = emptyList(), queueImportLoading = false, searchLoading = false,
                qrStatus = "登录成功", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0, message = "登录成功",
            ) }
            loadHome(); loadProfile(force = true); loadLibrary(); loadRecent()
        }.onFailure { error ->
            _state.update { s -> s.copy(qrStatus = "登录失败，请返回刷新二维码") }
            fail(error)
        }
    }
    fun logout() {
        viewModelScope.launch { runCatching { graph.downloads.pauseAll() }.onFailure { AppLog.write("DOWNLOAD", "logout ${it.javaClass.simpleName}:${it.message.orEmpty()}") } }
        playJob?.cancel(); playJob = null
        qualitySwitchJob?.cancel(); qualitySwitchJob = null
        recoveryJob?.cancel(); recoveryJob = null
        searchJob?.cancel(); searchJob = null; sessionGeneration++
        qrLoginJob?.cancel(); qrLoginJob = null
        detailJob?.cancel(); detailJob = null; queueImportJob?.cancel(); queueImportJob = null
        graph.playback.stopAndClear(); graph.vault.clear(); currentSession = null
        pendingQueue = null
        _queue.value = emptyList(); _queueIndex.value = -1; _queueReversed.value = false
        restoredPosition = 0; lastStreamUrl = ""; lastStreamExpiresAt = 0; retryingTrackId = null
        viewModelScope.launch { graph.settings.setPlaybackSnapshot(""); graph.settings.setProfileCache("") }
        _state.update { it.copy(
            home = null, library = null, recent = emptyList(), recentLoaded = false, profile = null, profileLoaded = false, profileError = null,
            searchTracks = emptyList(), searchCollections = emptyList(), searchCursor = null, searchLoading = false,
            currentTrack = null, lyrics = emptyList(), pendingSpeakerTrack = null, detail = null, detailDirectoryId = null,
            queueImportTitle = "", queueImportTracks = emptyList(), queueImportLoading = false,
            qrStatus = "", qrImageBase64 = "", qrMimeType = "", qrExpiresAt = 0,
            offlineSnapshot = false, message = "已退出，离线文件已保留并锁定",
        ) }
        loadHome()
    }
    fun loadLibrary() = viewModelScope.launch {
        if (!signedIn) return@launch fail(IllegalStateException("请先登录"))
        val generation = sessionGeneration
        restoreAccountSnapshot()
        if (generation != sessionGeneration) return@launch
        runCatching { graph.api.library() }.onSuccess { rawValue ->
            if (generation != sessionGeneration) return@onSuccess
            val value = normalizeLibraryData(rawValue)
            _state.update { it.copy(library = value, offlineSnapshot = false) }
            cacheAccountSnapshot(library = value)
        }.onFailure { error ->
            if (generation != sessionGeneration) return@onFailure
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
            library = state.library ?: cached.library?.let(::normalizeLibraryData),
            offlineSnapshot = true,
        ) }
    }

    private suspend fun cacheAccountSnapshot(home: HomeData? = null, library: LibraryData? = null) = snapshotMutex.withLock {
        val owner = accountId ?: "guest"
        val cache = runCatching { json.decodeFromString<CachedAccountSnapshots>(graph.settings.accountSnapshots.first()) }.getOrDefault(CachedAccountSnapshots())
        val old = cache.items.firstOrNull { it.accountId == owner }
        val normalizedLibrary = (library ?: old?.library)?.let(::normalizeLibraryData)
        val updated = CachedAccountSnapshot(owner, home ?: old?.home, normalizedLibrary, System.currentTimeMillis())
        graph.settings.setAccountSnapshots(json.encodeToString(upsertAccountSnapshot(cache, updated)))
    }
    fun loadProfile(force: Boolean = false) = viewModelScope.launch {
        val generation = sessionGeneration
        profileCacheReady.await()
        if (!signedIn || (!force && _state.value.profileLoaded)) return@launch
        if (!featureEnabled("profile")) return@launch _state.update { it.copy(profileLoaded = true, profileError = featureMessage("profile").ifBlank { "账号资料服务暂时维护" }) }
        runCatching { graph.api.profile() }.onSuccess { rawProfile ->
            if (generation != sessionGeneration) return@onSuccess
            val profile = normalizeUserProfile(rawProfile)
            _state.update { it.copy(profile = profile, profileLoaded = true, profileError = null) }
            graph.settings.setProfileCache(json.encodeToString(CachedUserProfile(accountId.orEmpty(), profile, System.currentTimeMillis())))
        }
            .onFailure { error ->
                if (generation != sessionGeneration) return@onFailure
                AppLog.write("PROFILE", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
                _state.update { it.copy(profileLoaded = true, profileError = "会员状态读取失败，可点检查登录重试") }
            }
    }
    /** Refreshes only the account profile, so the settings page can update VIP rights without a full playback diagnostic. */
    fun refreshMembership() = viewModelScope.launch {
        if (!signedIn) return@launch fail(IllegalStateException("请先登录"))
        _state.update { it.copy(profileError = null, message = "正在读取会员权益…") }
        loadProfile(force = true).join()
        val result = _state.value
        _state.update { it.copy(message = if (result.profileError == null) "会员权益已更新" else result.profileError) }
    }
    fun diagnose() = viewModelScope.launch {
        _state.update { it.copy(message = "正在检查登录、歌单和播放地址…") }
        val profileJob = loadProfile(force = true)
        runCatching { graph.api.diagnose() }
            .onSuccess { result -> _state.update { it.copy(message = result, diagnostic = result) } }
            .onFailure { error -> _state.update { it.copy(diagnostic = "诊断失败：${error.message ?: "未知错误"}") }; fail(error) }
        profileJob.join()
    }
    fun loadRecent() = viewModelScope.launch {
        val generation = sessionGeneration
        val owner = accountId ?: return@launch _state.update { it.copy(recent = emptyList(), recentLoaded = true) }
        val local = graph.db.recent().all(owner).map { Track(it.trackId, it.title, it.artists.split(" / "), it.album, it.artworkUrl, playedAt = it.playedAt) }
        val cloud = if (signedIn) runCatching { graph.api.recent() }.getOrDefault(emptyList()) else emptyList()
        if (generation != sessionGeneration) return@launch
        _state.update { it.copy(recent = mergeRecent(local, cloud), recentLoaded = true) }
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
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) {
                _state.update { it.copy(searchLoading = false) }
                fail(error)
            }
        }
    }
    fun clearSearchHistory() = viewModelScope.launch { graph.settings.clearSearchHistory() }
    fun featureEnabled(name: String): Boolean = _state.value.controlConfig.featureEnabled(name)
    fun featureMessage(name: String): String = _state.value.controlConfig.messages[name].orEmpty()

    private suspend fun restoreControlPlaneAndRefresh() {
        val cached = runCatching { json.decodeFromString<CachedControlPlane>(graph.settings.controlPlaneCache.first()) }.getOrNull()
        val now = System.currentTimeMillis()
        cached?.let { value -> _state.update { it.copy(
            controlConfig = value.config.takeIf { controlCacheIsFresh(value, now) } ?: RemoteFeatureConfig(),
            announcements = visibleAnnouncements(value.announcements, BuildConfig.VERSION_CODE, now),
            controlFetchedAt = value.fetchedAt,
        ) } }
        if (cached == null || !controlCacheIsFresh(cached, now)) refreshControlPlane(showStatus = false)
    }

    fun refreshControlPlane(showStatus: Boolean = true) = viewModelScope.launch {
        if (_state.value.controlRefreshing) return@launch
        _state.update { it.copy(controlRefreshing = true, controlError = null) }
        val (configResult, announcementsResult) = coroutineScope {
            val config = async { runCatching { graph.controlPlane.config() } }
            val announcements = async { runCatching { graph.controlPlane.announcements() } }
            config.await() to announcements.await()
        }
        val previous = _state.value
        if (configResult.isSuccess || announcementsResult.isSuccess) {
            val now = System.currentTimeMillis()
            val previousCache = CachedControlPlane(previous.controlConfig, previous.announcements, previous.controlFetchedAt)
            val partialError = configResult.exceptionOrNull() ?: announcementsResult.exceptionOrNull()
            val cache = CachedControlPlane(
                config = configResult.getOrNull()
                    ?: previous.controlConfig.takeIf { controlCacheIsFresh(previousCache, now) }
                    ?: RemoteFeatureConfig(),
                announcements = visibleAnnouncements(
                    announcementsResult.getOrDefault(previous.announcements),
                    BuildConfig.VERSION_CODE,
                    now,
                ),
                fetchedAt = now.takeIf { configResult.isSuccess } ?: previous.controlFetchedAt,
            )
            graph.settings.setControlPlaneCache(json.encodeToString(cache))
            _state.update { it.copy(
                controlConfig = cache.config, announcements = cache.announcements, controlFetchedAt = cache.fetchedAt,
                controlRefreshing = false, controlError = partialError?.message?.take(160),
                message = (if (partialError == null) "服务配置已同步" else "部分服务暂不可用，音乐功能继续直连")
                    .takeIf { showStatus },
            ) }
        } else {
            val message = configResult.exceptionOrNull()?.message ?: announcementsResult.exceptionOrNull()?.message ?: "控制面不可用"
            _state.update { current ->
                val cached = CachedControlPlane(current.controlConfig, current.announcements, current.controlFetchedAt)
                current.copy(
                    controlConfig = current.controlConfig.takeIf { controlCacheIsFresh(cached, System.currentTimeMillis()) } ?: RemoteFeatureConfig(),
                    announcements = visibleAnnouncements(current.announcements, BuildConfig.VERSION_CODE, System.currentTimeMillis()),
                    controlRefreshing = false, controlError = message.take(160),
                    message = "服务暂不可用，音乐功能继续直连".takeIf { showStatus },
                )
            }
        }
    }

    fun markAnnouncementSeen(id: String) = viewModelScope.launch { graph.settings.markAnnouncementSeen(id) }

    private suspend fun restorePendingUpdate() {
        val serialized = graph.settings.pendingUpdateRelease.first()
        if (serialized.isBlank()) return
        val release = runCatching { json.decodeFromString<ControlUpdate>(serialized) }.getOrNull()
        if (release == null || !release.hasUpdate || release.versionCode <= BuildConfig.VERSION_CODE) {
            graph.settings.setPendingUpdateRelease(null)
            return
        }
        val recovered = runCatching { graph.updates.recover(release) }.getOrElse {
            graph.settings.setPendingUpdateRelease(null)
            return
        }
        _state.update { current ->
            if (current.updateState != UpdateUiState.Idle) current else current.copy(
                updateState = recovered.ready?.let { UpdateUiState.Ready(release, it.absolutePath) }
                    ?: UpdateUiState.Available(release),
                message = when {
                    recovered.ready != null -> "已恢复校验通过的更新安装包"
                    recovered.partialBytes > 0 -> "上次下载已保留，可继续下载"
                    else -> current.message
                },
            )
        }
    }

    fun checkForUpdate() = viewModelScope.launch {
        _state.update { it.copy(updateState = UpdateUiState.Checking) }
        try {
            val release = graph.controlPlane.latestRelease()
            runCatching { graph.settings.setPendingUpdateRelease(json.encodeToString(release).takeIf { release.hasUpdate }) }
            _state.update { it.copy(
                updateState = if (release.hasUpdate) UpdateUiState.Available(release) else UpdateUiState.NoUpdate,
                message = if (release.hasUpdate) "发现新版本 ${release.versionName}" else "当前已是最新版本",
            ) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.update { it.copy(updateState = UpdateUiState.Error(error.message ?: "检查更新失败")) }
        }
    }

    fun downloadUpdate(release: ControlUpdate) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            runCatching { graph.settings.setPendingUpdateRelease(json.encodeToString(release)) }
            _state.update { it.copy(updateState = UpdateUiState.Downloading(release, 0, release.apk.sizeBytes)) }
            runCatching {
                graph.updates.downloadAndVerify(
                    release,
                    onProgress = { bytes, total -> _state.update { it.copy(updateState = UpdateUiState.Downloading(release, bytes, total)) } },
                    onVerifying = { _state.update { it.copy(updateState = UpdateUiState.Verifying(release)) } },
                )
            }.onSuccess { file -> _state.update { it.copy(updateState = UpdateUiState.Ready(release, file.absolutePath), message = "更新已校验，可以安装") } }
                .onFailure { error -> if (error !is CancellationException) _state.update { it.copy(updateState = UpdateUiState.Error(error.message ?: "更新下载失败", release)) } }
        }
    }

    fun submitDiagnostics() = viewModelScope.launch {
        if (!featureEnabled("diagnostics")) return@launch _state.update { it.copy(message = featureMessage("diagnostics").ifBlank { "诊断提交暂不可用" }) }
        _state.update { it.copy(diagnosticUploadState = DiagnosticUploadState.Uploading) }
        val payload = DiagnosticUpload(
            version = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
            sdk = android.os.Build.VERSION.SDK_INT,
            manufacturer = android.os.Build.MANUFACTURER.orEmpty().replace('\n', ' ').take(40),
            model = android.os.Build.MODEL.orEmpty().replace('\n', ' ').take(60),
            report = buildString {
                append("QMusic Watch diagnostics\n")
                append("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("sdk=${android.os.Build.VERSION.SDK_INT}\n")
                append("signedIn=${signedIn}\n")
                append("networkControl=${redactDiagnosticMessage(_state.value.controlError ?: "ok")}\n\n")
                append(AppLog.diagnosticExcerpt())
            }.take(58_000),
        )
        runCatching { graph.controlPlane.uploadDiagnostics(payload) }
            .onSuccess { receipt -> _state.update { it.copy(diagnosticUploadState = DiagnosticUploadState.Success(receipt.requestId), message = "诊断已提交") } }
            .onFailure { error -> _state.update { it.copy(diagnosticUploadState = DiagnosticUploadState.Error(error.message ?: "诊断提交失败")) } }
    }

    fun resetDiagnosticUpload() = _state.update { it.copy(diagnosticUploadState = DiagnosticUploadState.Idle) }

    fun prepareUpdateInstall(release: ControlUpdate, filePath: String, onReady: (File) -> Unit) = viewModelScope.launch {
        val file = File(filePath)
        _state.update { it.copy(updateState = UpdateUiState.Verifying(release)) }
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.updates.verifyDownloadedApk(file, release) }; file }
            .onSuccess { verified -> _state.update { it.copy(updateState = UpdateUiState.Ready(release, verified.absolutePath)) }; onReady(verified) }
            .onFailure { error -> _state.update { it.copy(updateState = UpdateUiState.Error(error.message ?: "安装包校验失败", release)) } }
    }
    fun requestPlay(track: Track, allowSpeaker: Boolean = false, sourceQueue: List<Track>? = null) {
        recoveryJob?.cancel(); recoveryJob = null; retryingTrackId = null
        qualitySwitchJob?.cancel(); qualitySwitchJob = null
        playJob?.cancel()
        playJob = viewModelScope.launch {
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
            if (local == null && !featureEnabled("stream")) error(featureMessage("stream").ifBlank { "在线播放暂时维护，仍可播放已缓存歌曲" })
            val stream = if (local == null) graph.api.stream(track, preferredQuality(track)) else null
            val uri = local?.let { android.net.Uri.fromFile(File(it.filePath)).toString() } ?: stream!!.url
            local?.let { item -> cachedArtworkFile(item.filePath).takeIf(File::exists)?.let { resolvedTrack = track.copy(artworkUrl = android.net.Uri.fromFile(it).toString()) } }
            issuedUrl = uri
            issuedExpiresAt = stream?.expiresAt ?: Long.MAX_VALUE
            issuedQuality = stream?.quality ?: local?.quality ?: preferredQuality(track)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            graph.playback.play(track.id, uri, track.title, track.artists.joinToString(" / "), resolvedTrack.artworkUrl)
            if (restoredPosition > 0 && _state.value.currentTrack?.id == track.id) graph.playback.seek(restoredPosition)
            graph.db.recent().upsert(RecentEntity(track.id, owner, track.title, track.artists.joinToString(" / "), track.album, track.artworkUrl, System.currentTimeMillis()))
            runCatching { loadLyrics(track, local?.filePath) }.onFailure { AppLog.write("LYRICS", "track=${track.id} ${it.javaClass.simpleName}:${it.message.orEmpty()}") }.getOrDefault(emptyList())
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
            _state.update {
                it.copy(
                    currentTrack = resolvedTrack,
                    activeStreamQuality = reportedQualityId(issuedQuality),
                    lyrics = parsedLyrics,
                    pendingSpeakerTrack = null,
                    playEvent = System.nanoTime(),
                    message = qualityFallbackMessage(quality.value, issuedQuality, track, it.profile),
                )
            }
            persistSnapshot()
        }.onFailure { error -> if (error !is CancellationException) failPlayback(error) }
        }
    }
    fun continueOnSpeaker() { _state.value.pendingSpeakerTrack?.let { requestPlay(it, true, pendingQueue) } }
    fun dismissSpeakerPrompt() = _state.update { it.copy(pendingSpeakerTrack = null) }
    private suspend fun loadLyrics(track: Track, localAudioPath: String?): List<LyricLine> {
        var data = localAudioPath?.let { cachedLyricsFile(it).takeIf(File::exists)?.let { file -> runCatching { json.decodeFromString<LyricsData>(file.readText()) }.getOrNull() } }
        if (data == null) {
            if (!featureEnabled("lyrics")) return emptyList()
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
    fun cache(track: Track, groupName: String = "单曲缓存", requestedQuality: String? = null) {
        val owner = accountId ?: return fail(IllegalStateException("请先登录"))
        viewModelScope.launch {
            runCatching { graph.downloads.enqueue(track, owner, requestedQuality ?: preferredQuality(track), wifiOnlyDownload.value, groupName) }
                .onSuccess { _state.update { s -> s.copy(message = if (wifiOnlyDownload.value) "已加入缓存，等待 Wi-Fi" else "已加入离线缓存") } }
                .onFailure(::fail)
        }
    }
    fun cacheAll(tracks: List<Track>, groupName: String = "播放列表缓存") = tracks.forEach { cache(it, groupName) }
    fun resumeDownload(item: com.ronan.qmusicwatch.data.DownloadEntity) = cache(
        Track(item.trackId, item.title, item.artists.split(" / "), artworkUrl = item.artworkUrl, qualities = allAudioQualitySpecs().map(AudioQualitySpec::id)),
        item.groupName,
        item.quality,
    )
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
    fun deleteLockedDownloads() = viewModelScope.launch {
        runCatching { graph.downloads.deleteLocked(accountId) }
            .onSuccess { count -> _state.update { it.copy(message = "已删除 $count 首其他账号锁定缓存") } }
            .onFailure(::fail)
    }
    fun deleteDownloadGroup(groupName: String) = viewModelScope.launch {
        val owner = accountId ?: return@launch fail(IllegalStateException("请先登录"))
        runCatching { graph.downloads.deleteGroup(owner, groupName) }
            .onSuccess { count -> _state.update { it.copy(message = "已删除“$groupName”中的 $count 首缓存") } }
            .onFailure(::fail)
    }
    fun like(track: Track, liked: Boolean) = viewModelScope.launch {
        if (!featureEnabled("playlistWrites")) return@launch fail(IllegalStateException(featureMessage("playlistWrites").ifBlank { "收藏与歌单编辑暂时维护" }))
        runCatching { graph.api.like(track, liked) }.onSuccess { loadLibrary() }.onFailure(::fail)
    }
    fun loadDetail(type: String, collection: MusicCollection, editable: Boolean = false) {
        detailJob?.cancel()
        val generation = sessionGeneration
        _state.update { it.copy(detail = null, detailDirectoryId = null) }
        detailJob = viewModelScope.launch {
            try {
                val value = graph.api.collection(type, collection)
                if (generation == sessionGeneration) _state.update { it.copy(detail = value, detailDirectoryId = collection.directoryId.takeIf { editable }) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { if (generation == sessionGeneration) fail(error) }
        }
    }
    fun createPlaylist(title: String) = viewModelScope.launch { if (requirePlaylistWrites()) runCatching { graph.api.createPlaylist(title) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun renamePlaylist(id: String, title: String) = viewModelScope.launch { if (requirePlaylistWrites()) runCatching { graph.api.renamePlaylist(id, title) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun deletePlaylist(id: String) = viewModelScope.launch { if (requirePlaylistWrites()) runCatching { graph.api.deletePlaylist(id) }.onSuccess { loadLibrary() }.onFailure(::fail) }
    fun addToPlaylist(track: Track, id: String) = viewModelScope.launch { if (requirePlaylistWrites()) runCatching { graph.api.changePlaylistTrack(id, track, true) }.onSuccess { _state.update { it.copy(message = "已加入歌单") }; loadLibrary() }.onFailure(::fail) }
    fun removeFromPlaylist(track: Track, id: String) = viewModelScope.launch {
        if (!requirePlaylistWrites()) return@launch
        runCatching { graph.api.changePlaylistTrack(id, track, false) }
            .onSuccess {
                val tracks = _state.value.detail?.tracks.orEmpty().filterNot { item -> item.id == track.id }
                _state.update { state -> state.copy(message = "已从歌单移除", detail = state.detail?.copy(tracks = tracks)) }
            }
            .onFailure(::fail)
    }
    fun setQuality(value: String) {
        qualitySwitchJob?.cancel()
        qualitySwitchJob = viewModelScope.launch {
            val requested = normalizeQualityId(value)
            val profile = _state.value.profile
            val entitlement = profileQualityOptions(profile).first { it.id == requested }
            if (!entitlement.available) {
                _state.update {
                    it.copy(message = entitlement.reason.ifBlank { "当前账号不能使用${qualityLabel(requested)}" })
                }
                return@launch
            }
            graph.settings.setQuality(requested)
            val track = _state.value.currentTrack
            if (track == null || lastStreamUrl.startsWith("file:") || lastStreamUrl.isBlank()) {
                _state.update { it.copy(message = "默认音质已设为${qualityLabel(requested)}") }
                return@launch
            }
            if (!featureEnabled("stream")) {
                _state.update { it.copy(message = "默认音质已设为${qualityLabel(requested)}，下次在线播放时生效") }
                return@launch
            }
            _state.update { it.copy(message = "正在切换到${qualityLabel(requested)}…") }
            try {
                val target = resolveQuality(requested, track, profile).resolved
                val stream = graph.api.stream(track, target)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (_state.value.currentTrack?.id != track.id || graph.playback.currentMediaId() != track.id) return@launch
                val position = graph.playback.position()
                val wasPlaying = graph.playback.isPlaying()
                graph.playback.play(track.id, stream.url, track.title, track.artists.joinToString(" / "), track.artworkUrl)
                graph.playback.seek(position)
                if (!wasPlaying) graph.playback.pause()
                lastStreamUrl = stream.url
                lastStreamExpiresAt = stream.expiresAt
                lastStreamQuality = stream.quality
                persistSnapshot(position)
                _state.update {
                    it.copy(
                        activeStreamQuality = normalizeQualityId(stream.quality),
                        message = qualityFallbackMessage(requested, stream.quality, track, profile)
                            ?: "已切换到${qualityLabel(stream.quality)}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failPlayback(error)
            }
        }
    }
    fun setHeadphoneWarning(value: Boolean) = viewModelScope.launch { graph.settings.setHeadphoneWarning(value) }
    fun setAutoOpenPlayer(value: Boolean) = viewModelScope.launch { graph.settings.setAutoOpenPlayer(value) }
    fun setPlayMode(value: String) = viewModelScope.launch { graph.settings.setPlayMode(value) }
    fun setLyricSize(value: String) = viewModelScope.launch { graph.settings.setLyricSize(value) }
    fun setLyricTranslation(value: Boolean) = viewModelScope.launch { graph.settings.setLyricTranslation(value) }
    fun setLyricOriginal(value: Boolean) = viewModelScope.launch { graph.settings.setLyricOriginal(value) }
    fun setLyricOffset(value: Long) = viewModelScope.launch { graph.settings.setLyricOffset(value) }
    fun setLyricAnimation(value: String) = viewModelScope.launch { graph.settings.setLyricAnimation(value) }
    fun setLyricAlignment(value: String) = viewModelScope.launch { graph.settings.setLyricAlignment(value) }
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
    private fun playAdjacent(delta: Int, ended: Boolean) {
        if (_queue.value.isEmpty()) return
        val mode = playMode.value
        val random = if (mode == "shuffle" && _queue.value.size > 1) _queue.value.indices.filter { it != _queueIndex.value }.random() else -1
        val target = nextQueueIndex(_queue.value.size, _queueIndex.value, delta, mode, ended, random)
        if (target < 0) return
        playQueueItem(target)
    }
    fun saveQueueAsPlaylist(title: String) = viewModelScope.launch {
        if (!requirePlaylistWrites()) return@launch
        runCatching {
            val playlist = graph.api.createPlaylist(title)
            _queue.value.forEach { graph.api.changePlaylistTrack(playlist.directoryId, it, true) }
        }.onSuccess { _state.update { it.copy(message = "播放列表已保存为歌单") }; loadLibrary() }.onFailure(::fail)
    }

    private fun requirePlaylistWrites(): Boolean {
        if (featureEnabled("playlistWrites")) return true
        fail(IllegalStateException(featureMessage("playlistWrites").ifBlank { "收藏与歌单编辑暂时维护" }))
        return false
    }
    fun loadQueueImportLiked() { queueImportJob?.cancel(); queueImportJob = null; _state.update { it.copy(queueImportTitle = "我喜欢", queueImportTracks = it.library?.liked.orEmpty(), queueImportLoading = false) } }
    fun loadQueueImportPlaylist(collection: MusicCollection) {
        queueImportJob?.cancel()
        val generation = sessionGeneration
        queueImportJob = viewModelScope.launch {
        _state.update { it.copy(queueImportTitle = collection.title, queueImportTracks = emptyList(), queueImportLoading = true) }
        try {
            val detail = graph.api.collection("playlist", collection)
            if (generation == sessionGeneration) _state.update { it.copy(queueImportTitle = detail.title, queueImportTracks = detail.tracks, queueImportLoading = false) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) {
            if (generation == sessionGeneration) { _state.update { it.copy(queueImportTitle = "", queueImportTracks = emptyList(), queueImportLoading = false) }; fail(error) }
        }
        }
    }
    fun clearQueueImport() { queueImportJob?.cancel(); queueImportJob = null; _state.update { it.copy(queueImportTitle = "", queueImportTracks = emptyList(), queueImportLoading = false) } }
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
    private fun preferredQuality(track: Track): String =
        resolveQuality(quality.value, track, _state.value.profile).resolved
    override fun onCleared() { graph.playback.onError = null; graph.playback.onMediaItemChanged = null; super.onCleared() }
}
