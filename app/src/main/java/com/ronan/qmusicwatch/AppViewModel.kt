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
    val detail: CollectionDetail? = null, val detailDirectoryId: String? = null,
    val detailLoading: Boolean = false, val detailError: String? = null, val playEvent: Long = 0,
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
    "${qualityLabel(preferred)}ä¸å¯ç”¨ï¼Œå·²è‡ªåŠ¨ä½¿ç”¨${qualityLabel(resolved)}".takeIf {
        normalizeQualityId(preferred) != normalizeQualityId(resolved)
    }

internal fun qualityFallbackMessage(preferred: String, resolved: String, track: Track, profile: UserProfile?): String? {
    if (normalizeQualityId(preferred) == normalizeQualityId(resolved)) return null
    val decision = resolveQuality(preferred, track, profile)
    val reason = decision.reason.ifBlank { "å½“å‰æ­Œæ›²æˆ–è´¦å·æœªæä¾›æ‰€é€‰éŸ³è´¨" }
    return "${qualityLabel(preferred)}ä¸å¯ç”¨ï¼š$reasonï¼Œå·²è‡ªåŠ¨ä½¿ç”¨${qualityLabel(resolved)}"
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
    private val sessionReady = CompletableDeferred<Unit>()
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
            try {
                prepareGatewayCredential()
            } finally {
                sessionReady.complete(Unit)
            }
        }
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
            sessionReady.await()
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
        viewModelScope.launch {
            restorePendingUpdate()
            checkForUpdateNow(showStatus = false)
        }
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
        viewModelScope.launch {
            sessionReady.await()
            if (signedIn) { loadLibrary(); loadRecent() }
        }
    }

    private suspend fun prepareGatewayCredential() {
        val session = currentSession ?: return
        if (!sessionNeedsGatewayCredentialRefresh(session)) return
        _state.update { it.copy(message = "æ­£åœ¨è¿ç§»ç™»å½•çŠ¶æ€â€¦") }
        runCatching {
            check(graph.api.refreshCredential(session.provider)) { "ç™»å½•å‡­æ®æ— æ³•åˆ·æ–°" }
            val refreshed = graph.vault.load()?.copy(gatewayHost = QMUSIC_SERVER_HOST)
                ?: error("ç™»å½•å‡­æ®ä¿å­˜å¤±è´¥")
            graph.vault.save(refreshed)
            currentSession = refreshed
        }.onSuccess {
            AppLog.write("AUTH", "gateway credential migration complete provider=${session.provider}")
            _state.update { it.copy(message = "ç™»å½•çŠ¶æ€å·²è¿ç§»") }
        }.onFailure { error ->
            AppLog.write("AUTH", "gateway credential migration failed ${error.javaClass.simpleName}")
            if (!requiresNewQrLogin(error)) {
                _state.update { it.copy(message = "ç™»å½•çŠ¶æ€è¿ç§»æš‚æ—¶å¤±è´¥ï¼Œç¨åå°†è‡ªåŠ¨é‡è¯•") }
                return@onFailure
            }
            graph.vault.clear()
            currentSession = null
            sessionGeneration++
            _state.update {
                it.copy(
                    profile = null,
                    profileLoaded = true,
                    profileError = "æœåŠ¡å™¨è¿ç§»åéœ€è¦é‡æ–°æ‰«ç ç™»å½•ä¸€æ¬¡",
                    message = "æœåŠ¡å™¨å·²æ›´æ¢ï¼Œè¯·é‡æ–°æ‰«ç ç™»å½•ä¸€æ¬¡",
                )
            }
     ×mõÒÚ$z{-®éÜj×vRÒ.[{.XŠ™šBF6÷VçBšinX[nK¹n‹JnXû~™HZé®{É>ZÙ‚"’ÒĞ¢æöäf–ÇW&Rƒ£¦f–Â¢Ğ¢gVâFVÆWFTF÷væÆöDw&÷W†w&÷WæÖS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢fÂ÷væW"Ò66÷VçD–Bó¢&WGW&äÆVæ6‚f–Â„–ÆÆVvÅ7FFTW†6WF–öâ‚.Šû~XXy›¾[ÙR"’¢'Vä6F6†–ær²w&‚æF÷væÆöG2æFVÆWFTw&÷W†÷væW"Âw&÷WæÖR’Ğ¢æöå7V66W72²6÷VçBÓâ÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.XŠ™šN(	ÂFw&÷WæÖ^(	ŞKŠŞy¨BF6÷VçBšin{É>ZÙ‚"’ÒĞ¢æöäf–ÇW&Rƒ£¦f–Â¢Ğ¢gVâÆ–¶R‡G&6³¢G&6²ÂÆ–¶VC¢&ööÆVâÂöä6ö×ÆWFS¢„&ööÆVâ’ÓâVæ—BÒ·Ò’Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢–b‚fVGW&TVæ&ÆVB‚'Æ–Æ—7Ew&—FW2"’’°¢f–Â„–ÆÆVvÅ7FFTW†6WF–öâ†fVGW&TÖW76vR‚'Æ–Æ—7Ew&—FW2"’æ–d&Ææ²².iKn‰xşKˆîjØÎXÙ^{Én‹éi¨.i{n{»NhªB"Ò’¢öä6ö×ÆWFR†fÇ6R¢&WGW&äÆVæ6€¢Ğ¢'Vä6F6†–ær²w&‚æ’æÆ–¶R‡G&6²ÂÆ–¶VB’Ğ¢æöå7V66W72°¢÷7FFRçWFFR²7FFRÓâ7FFRæ6÷’†ÖW76vRÒ–b†Æ–¶VB’.[{.k{¾XªX‹h‰YiÎjÊ""VÇ6R.[{.XùnkhYiÎjÊ""’Ğ¢öä6ö×ÆWFR‡G'VR¢ÆöDÆ–'&'’‚¢Ğ¢æöäf–ÇW&R²W'&÷"Óà¢öä6ö×ÆWFR†fÇ6R¢f–Â†W'&÷"¢Ğ¢Ğ¢gVâÆöDFWF–Â‡G—S¢7G&–ærÂ6öÆÆV7F–öã¢×W6–46öÆÆV7F–öâÂVF—F&ÆS¢&ööÆVâÒfÇ6R’°¢FWF–Ä¦ö#òæ6æ6VÂ‚¢fÂvVæW&F–öâÒ6W76–öävVæW&F–öà¢÷7FFRçWFFR²—Bæ6÷’†FWF–ÂÒçVÆÂÂFWF–ÄF—&V7F÷'”–BÒçVÆÂÂFWF–ÄÆöF–ærÒG'VRÂFWF–ÄW'&÷"ÒçVÆÂ’Ğ¢FWF–Ä¦ö"Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢G'’°¢fÂfÇVRÒw&‚æ’æ6öÆÆV7F–öâ‡G—RÂ6öÆÆV7F–öâ¢–b†vVæW&F–öâÓÒ6W76–öävVæW&F–öâ’÷7FFRçWFFR°¢—Bæ6÷’€¢FWF–ÂÒfÇVRÀ¢FWF–ÄF—&V7F÷'”–BÒ6öÆÆV7F–öâæF—&V7F÷'”–BçF¶T–b²VF—F&ÆRÒÀ¢FWF–ÄÆöF–ærÒfÇ6RÀ¢FWF–ÄW'&÷"ÒçVÆÂÀ¢¢Ğ¢Ò6F6‚†6æ6VÆÆVC¢6æ6VÆÆF–öäW†6WF–öâ’°¢F‡&÷r6æ6VÆÆV@¢Ò6F6‚†W'&÷#¢F‡&÷v&ÆR’°¢–b†vVæW&F–öâÓÒ6W76–öävVæW&F–öâ’°¢÷7FFRçWFFR°¢—Bæ6÷’€¢FWF–ÄÆöF–ærÒfÇ6RÀ¢FWF–ÄW'&÷"ÒW'&÷"æÖW76vSòçF¶T–b…7G&–æs£¦—4æ÷D&Ææ²’ó¢.jØÎXÙ^Šû¾XùnZK‹JR"À¢¢Ğ¢f–Â†W'&÷"¢Ğ¢Ğ¢Ğ¢Ğ¢gVâ7&VFUÆ–Æ—7B‡F—FÆS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²–b‡&WV—&UÆ–Æ—7Ew&—FW2‚’’'Vä6F6†–ær²w&‚æ’æ7&VFUÆ–Æ—7B‡F—FÆR’Òæöå7V66W72²ÆöDÆ–'&'’‚’Òæöäf–ÇW&Rƒ£¦f–Â’Ğ¢gVâ&VæÖUÆ–Æ—7B†–C¢7G&–ærÂF—FÆS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²–b‡&WV—&UÆ–Æ—7Ew&—FW2‚’’'Vä6F6†–ær²w&‚æ’ç&VæÖUÆ–Æ—7B†–BÂF—FÆR’Òæöå7V66W72²ÆöDÆ–'&'’‚’Òæöäf–ÇW&Rƒ£¦f–Â’Ğ¢gVâFVÆWFUÆ–Æ—7B†–C¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²–b‡&WV—&UÆ–Æ—7Ew&—FW2‚’’'Vä6F6†–ær²w&‚æ’æFVÆWFUÆ–Æ—7B†–B’Òæöå7V66W72²ÆöDÆ–'&'’‚’Òæöäf–ÇW&Rƒ£¦f–Â’Ğ¢gVâFEFõÆ–Æ—7B‡G&6³¢G&6²Â–C¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²–b‡&WV—&UÆ–Æ—7Ew&—FW2‚’’'Vä6F6†–ær²w&‚æ’æ6†ævUÆ–Æ—7EG&6²†–BÂG&6²ÂG'VR’Òæöå7V66W72²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.XªXZ^jØÎXÙR"’Ó²ÆöDÆ–'&'’‚’Òæöäf–ÇW&Rƒ£¦f–Â’Ğ¢gVâ&VÖ÷fTg&öÕÆ–Æ—7B‡G&6³¢G&6²Â–C¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢–b‚&WV—&UÆ–Æ—7Ew&—FW2‚’’&WGW&äÆVæ6€¢'Vä6F6†–ær²w&‚æ’æ6†ævUÆ–Æ—7EG&6²†–BÂG&6²ÂfÇ6R’Ğ¢æöå7V66W72°¢fÂG&6·2Ò÷7FFRçfÇVRæFWF–ÃòçG&6·2æ÷$V×G’‚’æf–ÇFW$æ÷B²—FVÒÓâ—FVÒæ–BÓÒG&6²æ–BĞ¢÷7FFRçWFFR²7FFRÓâ7FFRæ6÷’†ÖW76vRÒ.[{.K¸îjØÎXÙ^z{¾™šB"ÂFWF–ÂÒ7FFRæFWF–Ãòæ6÷’‡G&6·2ÒG&6·2’’Ğ¢Ğ¢æöäf–ÇW&Rƒ£¦f–Â¢Ğ¢gVâ6WEVÆ—G’‡fÇVS¢7G&–ær’°¢VÆ—G•7v—F6„¦ö#òæ6æ6VÂ‚¢VÆ—G•7v—F6„¦ö"Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢fÂ&WVW7FVBÒæ÷&ÖÆ—¦UVÆ—G”–B‡fÇVR¢fÂ&öf–ÆRÒ÷7FFRçfÇVRç&öf–ÆP¢fÂVçF—FÆVÖVçBÒ&öf–ÆUVÆ—G”÷F–öç2‡&öf–ÆR’æf—'7B²—Bæ–BÓÒ&WVW7FVBĞ¢–b‚VçF—FÆVÖVçBæf–Æ&ÆR’°¢÷7FFRçWFFR°¢—Bæ6÷’†ÖW76vRÒVçF—FÆVÖVçBç&V6öâæ–d&Ææ²².[Ù>X˜Ş‹JnXû~KˆŞˆ;ŞKÛşyJ‚G·VÆ—G”Æ&VÂ‡&WVW7FVB—Ò"Ò¢Ğ¢&WGW&äÆVæ6€¢Ğ¢w&‚ç6WGF–æw2ç6WEVÆ—G’‡&WVW7FVB¢fÂG&6²Ò÷7FFRçfÇVRæ7W'&VçEG&6°¢–b‡G&6²ÓÒçVÆÂÇÂÆ7E7G&VÕW&Âç7F'G5v—F‚‚&f–ÆS¢"’ÇÂÆ7E7G&VÕW&Âæ—4&Ææ²‚’’°¢÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.›¹ŠêN™û>‹J[{.ŠëîK‹¢G·VÆ—G”Æ&VÂ‡&WVW7FVB—Ò"’Ğ¢&WGW&äÆVæ6€¢Ğ¢–b‚fVGW&TVæ&ÆVB‚'7G&VÒ"’’°¢÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.›¹ŠêN™û>‹J[{.ŠëîK‹¢G·VÆ—G”Æ&VÂ‡&WVW7FVB—ŞûÈÎKˆ¾jÊYÊ{«şi*ŞiKîi{nyIşiX‚"’Ğ¢&WGW&äÆVæ6€¢Ğ¢÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.jÚ>YÊXˆ~hÚ.X‹G·VÆ—G”Æ&VÂ‡&WVW7FVB—Ş(
b"’Ğ¢G'’°¢fÂF&vWBÒ&W6öÇfUVÆ—G’‡&WVW7FVBÂG&6²Â&öf–ÆR’ç&W6öÇfV@¢fÂ7G&VÒÒw&‚æ’ç7G&VÒ‡G&6²ÂF&vWB¢¶÷FÆ–ç‚æ6÷&÷WF–æW2æ7W'&VçD6÷&÷WF–æT6öçFW‡B‚’æVç7W&T7F—fR‚¢–b…÷7FFRçfÇVRæ7W'&VçEG&6³òæ–BÒG&6²æ–BÇÂw&‚çÆ–&6²æ7W'&VçDÖVF––B‚’ÒG&6²æ–B’&WGW&äÆVæ6€¢fÂ÷6—F–öâÒw&‚çÆ–&6²ç÷6—F–öâ‚¢fÂÆ•v†Vå&VG’Òw&‚çÆ–&6²çÆ•v†Vå&VG’‚¢w&‚çÆ–&6²ç&WÆ6U7G&VÒ€¢–BÒG&6²æ–BÀ¢W&’Ò7G&VÒçW&ÂÀ¢F—FÆRÒG&6²çF—FÆRÀ¢'F—7BÒG&6²æ'F—7G2æ¦ö–åFõ7G&–ær‚"ò"’À¢'Gv÷&²ÒG&6²æ'Gv÷&µW&ÂÀ¢7F'E÷6—F–öä×2Ò÷6—F–öâÀ¢Æ•v†Vå&VG’ÒÆ•v†Vå&VG’À¢¢Æ7E7G&VÕW&ÂÒ7G&VÒçW&À¢Æ7E7G&VÔW‡—&W4BÒ7G&VÒæW‡—&W4@¢Æ7E7G&VÕVÆ—G’Ò7G&VÒçVÆ—G¢W'6—7E6æ6†÷B‡÷6—F–öâ¢÷7FFRçWFFR°¢—Bæ6÷’€¢7F—fU7G&VÕVÆ—G’Òæ÷&ÖÆ—¦UVÆ—G”–B‡7G&VÒçVÆ—G’’À¢ÖW76vRÒVÆ—G”fÆÆ&6´ÖW76vR‡&WVW7FVBÂ7G&VÒçVÆ—G’ÂG&6²Â&öf–ÆR¢ó¢.[{.Xˆ~hÚ.X‹G·VÆ—G”Æ&VÂ‡7G&VÒçVÆ—G’—Ò"À¢¢Ğ¢Ò6F6‚†6æ6VÆÆVC¢6æ6VÆÆF–öäW†6WF–öâ’°¢F‡&÷r6æ6VÆÆV@¢Ò6F6‚†W'&÷#¢F‡&÷v&ÆR’°¢f–ÅÆ–&6²†W'&÷"¢Ğ¢Ğ¢Ğ¢gVâ6WD†VG†öæUv&æ–ær‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WD†VG†öæUv&æ–ær‡fÇVR’Ğ¢gVâ6WDWFô÷VåÆ–W"‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDWFô÷VåÆ–W"‡fÇVR’Ğ¢gVâ6WEÆ”ÖöFR‡fÇVS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WEÆ”ÖöFR‡fÇVR’Ğ¢gVâ6WDÇ—&–56—¦R‡fÇVS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–56—¦R‡fÇVR’Ğ¢gVâ6WDÇ—&–5G&ç6ÆF–öâ‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–5G&ç6ÆF–öâ‡fÇVR’Ğ¢gVâ6WDÇ—&–4÷&–v–æÂ‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–4÷&–v–æÂ‡fÇVR’Ğ¢gVâ6WDÇ—&–4öfg6WB‡fÇVS¢Æöær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–4öfg6WB‡fÇVR’Ğ¢gVâ6WDÇ—&–4æ–ÖF–öâ‡fÇVS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–4æ–ÖF–öâ‡fÇVR’Ğ¢gVâ6WDÇ—&–4Æ–væÖVçB‡fÇVS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÇ—&–4Æ–væÖVçB‡fÇVR’Ğ¢gVâ6WEW&T&Æ6²‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WEW&T&Æ6²‡fÇVR’Ğ¢gVâ6WDÆ÷u÷vW%Æ–W"‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÆ÷u÷vW%Æ–W"‡fÇVR’Ğ¢gVâ6WEv–f”öæÇ”F÷væÆöB‡fÇVS¢&ööÆVâ’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WEv–f”öæÇ”F÷væÆöB‡fÇVR’Ğ¢gVâ6WDF–Ç”6÷VçB‡fÇVS¢–çB’Òf–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDF–Ç”6÷VçB‡fÇVR’Ğ¢gVâFEFõVWVR‡G&6³¢G&6²’²–b…÷VWVRçfÇVRææöæR²—Bæ–BÓÒG&6²æ–BÒ’÷VWVRçfÇVRÒ÷VWVRçfÇVR²G&6³²W'6—7E6æ6†÷B‚“²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.XªXZ^i*ŞiKîX‰~Š‚"’ÒĞ¢gVâVçVWVTæW‡B‡G&6³¢G&6²’°¢fÂ—FV×2Ò–ç6W'DæW‡B…÷VWVRçfÇVRÂ÷7FFRçfÇVRæ7W'&VçEG&6³òæ–BÂG&6²¢÷VWVRçfÇVRÒ—FV×0¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²Æ––ærÓâ—FV×2æ–æFW„ödf—'7B²—Bæ–BÓÒÆ––æræ–BÒÒó¢Ó¢W'6—7E6æ6†÷B‚¢÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.ŠëîK‹®Kˆ¾Kˆšib"’Ğ¢Ğ¢gVâ&VÖ÷fTg&öÕVWVR†–æFWƒ¢–çB’°¢fÂ—FV×2Ò÷VWVRçfÇVRçFô×WF&ÆTÆ—7B‚¢–b†–æFW‚–â—FV×2æ–æF–6W2’&WGW&à¢—FV×2ç&VÖ÷fTB†–æFW‚“²÷VWVRçfÇVRÒ—FV×0¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²Æ––ærÓâ—FV×2æ–æFW„ödf—'7B²—Bæ–BÓÒÆ––æræ–BÒÒó¢Ó¢W'6—7E6æ6†÷B‚¢Ğ¢gVâ6ÆV%VWVR‚’²÷VWVRçfÇVRÒV×G”Æ—7B‚“²÷VWVT–æFW‚çfÇVRÒÓ²W'6—7E6æ6†÷B‚’Ğ¢gVâ&VÖ÷fUVWVTGWÆ–6FW2‚’°¢fÂ&Vf÷&RÒ÷VWVRçfÇVRç6—¦P¢÷VWVRçfÇVRÒ÷VWVRçfÇVRæF—7F–æ7D'’…G&6³£¦–B¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²Æ––ærÓâ÷VWVRçfÇVRæ–æFW„ödf—'7B²—Bæ–BÓÒÆ––æræ–BÒÒó¢Ó¢W'6—7E6æ6†÷B‚“²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.z{¾™šBG¶&Vf÷&RÒ÷VWVRçfÇVRç6—¦WÒšin˜xŞZHŞjØÎi»""’Ğ¢Ğ¢gVâ&WfW'6UVWVR‚’°¢÷VWVRçfÇVRÒ÷VWVRçfÇVRç&WfW'6VB‚“²÷VWVU&WfW'6VBçfÇVRÒ÷VWVU&WfW'6VBçfÇVP¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²Æ––ærÓâ÷VWVRçfÇVRæ–æFW„ödf—'7B²—Bæ–BÓÒÆ––æræ–BÒÒó¢Ó¢W'6—7E6æ6†÷B‚¢Ğ¢gVâ&WÆ6UVWVT÷&FW"†—FV×3¢Æ—7CÅG&6³â’°¢–b†—FV×2æÖ…G&6³£¦–B’ç6÷'FVB‚’Ò÷VWVRçfÇVRæÖ…G&6³£¦–B’ç6÷'FVB‚’’&WGW&à¢–b†—FV×2æÖ…G&6³£¦–B’ÓÒ÷VWVRçfÇVRæÖ…G&6³£¦–B’’&WGW&à¢÷VWVRçfÇVRÒ—FV×0¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²Æ––ærÓâ—FV×2æ–æFW„ödf—'7B²—Bæ–BÓÒÆ––æræ–BÒÒó¢Ó¢W'6—7E6æ6†÷B‚¢Ğ¢gVâÆ•VWVT—FVÒ†–æFWƒ¢–çB’²÷VWVRçfÇVRævWD÷$çVÆÂ†–æFW‚“òæÆWB²&WVW7EÆ’†—BÂG'VR’ÒĞ¢gVâ6¶—æW‡B‚’ÒÆ”F¦6VçBƒÂfÇ6R¢gVâ6¶—&Wf–÷W2‚’ÒÆ”F¦6VçB‚ÓÂfÇ6R¢&—fFRgVâÆ”F¦6VçB†FVÇF¢–çBÂVæFVC¢&ööÆVâ’°¢–b…÷VWVRçfÇVRæ—4V×G’‚’’&WGW&à¢fÂÖöFRÒÆ”ÖöFRçfÇVP¢fÂ&æFöÒÒ–b†ÖöFRÓÒ'6‡VffÆR"bb÷VWVRçfÇVRç6—¦Râ’÷VWVRçfÇVRæ–æF–6W2æf–ÇFW"²—BÒ÷VWVT–æFW‚çfÇVRÒç&æFöÒ‚’VÇ6RÓ¢fÂF&vWBÒæW‡EVWVT–æFW‚…÷VWVRçfÇVRç6—¦RÂ÷VWVT–æFW‚çfÇVRÂFVÇFÂÖöFRÂVæFVBÂ&æFöÒ¢–b‡F&vWBÂ’&WGW&à¢Æ•VWVT—FVÒ‡F&vWB¢Ğ¢gVâ6fUVWVT5Æ–Æ—7B‡F—FÆS¢7G&–ær’Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢–b‚&WV—&UÆ–Æ—7Ew&—FW2‚’’&WGW&äÆVæ6€¢'Vä6F6†–ær°¢fÂÆ–Æ—7BÒw&‚æ’æ7&VFUÆ–Æ—7B‡F—FÆR¢÷VWVRçfÇVRæf÷$V6‚²w&‚æ’æ6†ævUÆ–Æ—7EG&6²‡Æ–Æ—7BæF—&V7F÷'”–BÂ—BÂG'VR’Ğ¢Òæöå7V66W72²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.i*ŞiKîX‰~Š[{.KùŞZÙK‹®jØÎXÙR"’Ó²ÆöDÆ–'&'’‚’Òæöäf–ÇW&Rƒ£¦f–Â¢Ğ ¢&—fFRgVâ&WV—&UÆ–Æ—7Ew&—FW2‚“¢&ööÆVâ°¢–b†fVGW&TVæ&ÆVB‚'Æ–Æ—7Ew&—FW2"’’&WGW&âG'VP¢f–Â„–ÆÆVvÅ7FFTW†6WF–öâ†fVGW&TÖW76vR‚'Æ–Æ—7Ew&—FW2"’æ–d&Ææ²².iKn‰xşKˆîjØÎXÙ^{Én‹éi¨.i{n{»NhªB"Ò’¢&WGW&âfÇ6P¢Ğ¢gVâÆöEVWVT–×÷'DÆ–¶VB‚’²VWVT–×÷'D¦ö#òæ6æ6VÂ‚“²VWVT–×÷'D¦ö"ÒçVÆÃ²÷7FFRçWFFR²—Bæ6÷’‡VWVT–×÷'EF—FÆRÒ.h‰YiÎjÊ""ÂVWVT–×÷'EG&6·2Ò—BæÆ–'&'“òæÆ–¶VBæ÷$V×G’‚’ÂVWVT–×÷'DÆöF–ærÒfÇ6R’ÒĞ¢gVâÆöEVWVT–×÷'EÆ–Æ—7B†6öÆÆV7F–öã¢×W6–46öÆÆV7F–öâ’°¢VWVT–×÷'D¦ö#òæ6æ6VÂ‚¢fÂvVæW&F–öâÒ6W76–öävVæW&F–öà¢VWVT–×÷'D¦ö"Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢÷7FFRçWFFR²—Bæ6÷’‡VWVT–×÷'EF—FÆRÒ6öÆÆV7F–öâçF—FÆRÂVWVT–×÷'EG&6·2ÒV×G”Æ—7B‚’ÂVWVT–×÷'DÆöF–ærÒG'VR’Ğ¢G'’°¢fÂFWF–ÂÒw&‚æ’æ6öÆÆV7F–öâ‚'Æ–Æ—7B"Â6öÆÆV7F–öâ¢–b†vVæW&F–öâÓÒ6W76–öävVæW&F–öâ’÷7FFRçWFFR²—Bæ6÷’‡VWVT–×÷'EF—FÆRÒFWF–ÂçF—FÆRÂVWVT–×÷'EG&6·2ÒFWF–ÂçG&6·2ÂVWVT–×÷'DÆöF–ærÒfÇ6R’Ğ¢Ò6F6‚†6æ6VÆÆVC¢6æ6VÆÆF–öäW†6WF–öâ’²F‡&÷r6æ6VÆÆVBÒ6F6‚†W'&÷#¢F‡&÷v&ÆR’°¢–b†vVæW&F–öâÓÒ6W76–öävVæW&F–öâ’²÷7FFRçWFFR²—Bæ6÷’‡VWVT–×÷'EF—FÆRÒ""ÂVWVT–×÷'EG&6·2ÒV×G”Æ—7B‚’ÂVWVT–×÷'DÆöF–ærÒfÇ6R’Ó²f–Â†W'&÷"’Ğ¢Ğ¢Ğ¢Ğ¢gVâ6ÆV%VWVT–×÷'B‚’²VWVT–×÷'D¦ö#òæ6æ6VÂ‚“²VWVT–×÷'D¦ö"ÒçVÆÃ²÷7FFRçWFFR²—Bæ6÷’‡VWVT–×÷'EF—FÆRÒ""ÂVWVT–×÷'EG&6·2ÒV×G”Æ—7B‚’ÂVWVT–×÷'DÆöF–ærÒfÇ6R’ÒĞ¢gVâFE6VÆV7FVEVWVUG&6·2†–G3¢6WCÅ7G&–æsâ’°¢fÂ&Vf÷&RÒ÷VWVRçfÇVRç6—¦P¢÷VWVRçfÇVRÒÖW&vU6VÆV7FVEVWVR…÷VWVRçfÇVRÂ÷7FFRçfÇVRçVWVT–×÷'EG&6·2Â–G2¢÷VWVT–æFW‚çfÇVRÒ÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²7W'&VçBÓâ÷VWVRçfÇVRæ–æFW„ödf—'7B²—Bæ–BÓÒ7W'&VçBæ–BÒÒó¢Ó¢fÂFFVBÒ÷VWVRçfÇVRç6—¦RÒ&Vf÷&P¢W'6—7E6æ6†÷B‚“²6ÆV%VWVT–×÷'B‚“²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.XªXZRFFFVBšinjØÎi»""’Ğ¢Ğ¢gVâ7F'E6ÆVWF–ÖW"†Ö–çWFW3¢–çBÂf–æ—6„7W'&VçC¢&ööÆVâÒfÇ6R’²w&‚çÆ–&6²ç7F'E6ÆVWF–ÖW"†Ö–çWFW2Âf–æ—6„7W'&VçB“²f–WtÖöFVÅ66÷RæÆVæ6‚²w&‚ç6WGF–æw2ç6WDÆ7E6ÆVWÖ–çWFW2†Ö–çWFW2’Ó²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ–b†f–æ—6„7W'&VçB’"FÖ–çWFW2Xˆn™)şYîi*ŞZèÎ[Ù>X˜ŞjØÎi».X[>™zÒ"VÇ6R.[nYÊ‚FÖ–çWFW2Xˆn™)şYîXÎjÚ.i*ŞiKâ"’ÒĞ¢gVâ6æ6VÅ6ÆVWF–ÖW"‚’²w&‚çÆ–&6²æ6æ6VÅ6ÆVWF–ÖW"‚“²÷7FFRçWFFR²—Bæ6÷’†ÖW76vRÒ.[{.XùnkhZé®i{nX[>™zÒ"’ÒĞ¢gVâÆ–&6µ÷6—F–öâ‚’Òw&‚çÆ–&6²ç÷6—F–öâ‚¢gVâÆ–&6´GW&F–öâ‚’Òw&‚çÆ–&6²æGW&F–öâ‚¢gVâ—5Æ––ær‚’Òw&‚çÆ–&6²æ—5Æ––ær‚¢gVâW6UÆ–&6²‚’²w&‚çÆ–&6²çW6R‚“²W'6—7E6æ6†÷B‚’Ğ¢gVâ&W7VÖUÆ–&6²‚’²–b†w&‚çÆ–&6²æGW&F–öâ‚’ÓÒÂ’÷7FFRçfÇVRæ7W'&VçEG&6³òæÆWB²&WVW7EÆ’†—BÂG'VR’ÒVÇ6Rw&‚çÆ–&6²ç&W7VÖR‚’Ğ¢gVâ6VV²‡÷6—F–öã¢Æöær’Òw&‚çÆ–&6²ç6VV²‡÷6—F–öâ¢gVâF§W7EföÇVÖR†F—&V7F–öã¢–çB’Òw&‚çÆ–&6²æF§W7EföÇVÖR†F—&V7F–öâ¢gVâ6fUÆ–&6µ7FFR‚’ÒW'6—7E6æ6†÷B‚¢&—fFRgVâW'6—7E6æ6†÷B‡÷6—F–öã¢ÆöærÒw&‚çÆ–&6²ç÷6—F–öâ‚’’Òf–WtÖöFVÅ66÷RæÆVæ6‚°¢fÂ÷væW"Ò66÷VçD–Bó¢&WGW&äÆVæ6€¢w&‚ç6WGF–æw2ç6WEÆ–&6µ6æ6†÷B†§6öâæVæ6öFUFõ7G&–ær…Æ–&6µ6æ6†÷B€¢÷7FFRçfÇVRæ7W'&VçEG&6²Â÷VWVRçfÇVRÂ÷6—F–öâÂ÷VWVU&WfW'6VBçfÇVRÀ¢Æ7E7G&VÕW&ÂÂÆ7E7G&VÔW‡—&W4BÂÆ7E7G&VÕVÆ—G’Â÷væW"À¢’’¢Ğ¢&—fFRgVâ&VfW'&VEVÆ—G’‡G&6³¢G&6²“¢7G&–ærĞ¢&W6öÇfUVÆ—G’‡VÆ—G’çfÇVRÂG&6²Â÷7FFRçfÇVRç&öf–ÆR’ç&W6öÇfV@¢÷fW'&–FRgVâöä6ÆV&VB‚’²w&‚çÆ–&6²æöäW'&÷"ÒçVÆÃ²w&‚çÆ–&6²æöäÖVF–—FVÔ6†ævVBÒçVÆÃ²7WW"æöä6ÆV&VB‚’Ğ§Ğ 