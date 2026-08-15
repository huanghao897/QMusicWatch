package com.ronan.qmusicwatch.playback

import android.content.*
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import okhttp3.OkHttpClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ronan.qmusicwatch.QMusicApplication
import com.ronan.qmusicwatch.nextQueueIndex
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.data.RecentEntity
import com.ronan.qmusicwatch.download.cachedArtworkFile
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import com.ronan.qmusicwatch.model.StreamData
import com.ronan.qmusicwatch.model.belongsToAccount
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import com.ronan.qmusicwatch.network.withQqMusicMediaHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

private const val COMMAND_PREVIOUS = "com.ronan.qmusicwatch.PREVIOUS"
private const val COMMAND_NEXT = "com.ronan.qmusicwatch.NEXT"
internal const val BACKGROUND_PLAYBACK_WAKE_MODE = C.WAKE_MODE_NETWORK
internal const val BACKGROUND_SNAPSHOT_INTERVAL_MS = 10_000L
internal const val BACKGROUND_RECOVERY_DELAY_MS = 500L
internal const val BACKGROUND_RECOVERY_ATTEMPTS = 3

@androidx.annotation.OptIn(UnstableApi::class)
internal class QMusicLoadErrorHandlingPolicy :
    DefaultLoadErrorHandlingPolicy(BACKGROUND_RECOVERY_ATTEMPTS) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (requiresImmediateStreamRefresh(loadErrorInfo.exception)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

internal data class PlaybackRecoveryRequest(
    val generation: Long = 0L,
    val mediaId: String,
    val failedUri: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

internal class PlaybackRecoveryTracker {
    private var nextGeneration = 0L
    private var pending: PlaybackRecoveryRequest? = null
    private var expectedReplacement: Pair<Long, String>? = null

    @Synchronized
    fun replace(candidate: PlaybackRecoveryRequest): PlaybackRecoveryRequest =
        candidate.copy(generation = ++nextGeneration).also {
            pending = it
            expectedReplacement = null
        }

    @Synchronized
    fun current(): PlaybackRecoveryRequest? = pending

    @Synchronized
    fun isCurrent(request: PlaybackRecoveryRequest): Boolean =
        pending?.generation == request.generation

    @Synchronized
    fun clearIfCurrent(request: PlaybackRecoveryRequest): Boolean {
        if (!isCurrent(request)) return false
        pending = null
        expectedReplacement = null
        return true
    }

    @Synchronized
    fun expectReplacement(request: PlaybackRecoveryRequest, uri: String): Boolean {
        if (!isCurrent(request)) return false
        expectedReplacement = request.generation to uri
        return true
    }

    @Synchronized
    fun invalidateUnlessMatches(
        mediaId: String?,
        uri: String,
        applyingGeneration: Long?,
    ): PlaybackRecoveryRequest? {
        val request = pending ?: return null
        if (applyingGeneration == request.generation && mediaId == request.mediaId) return null
        if (mediaId == request.mediaId && uri == request.failedUri) return null
        if (mediaId == request.mediaId &&
            expectedReplacement == (request.generation to uri)
        ) return null
        pending = null
        expectedReplacement = null
        return request
    }
}

internal fun mergeRecoveredPlaybackSnapshot(
    snapshot: PlaybackSnapshot,
    accountId: String?,
    request: PlaybackRecoveryRequest,
    stream: StreamData,
): PlaybackSnapshot? {
    if (!snapshot.belongsToAccount(accountId) || snapshot.track?.id != request.mediaId) return null
    return snapshot.copy(
        positionMs = request.positionMs,
        streamUrl = stream.url,
        streamExpiresAt = stream.expiresAt,
        quality = stream.quality,
    )
}

internal fun mergePlaybackProgressSnapshot(
    snapshot: PlaybackSnapshot,
    accountId: String?,
    mediaId: String,
    uri: String,
    positionMs: Long,
): PlaybackSnapshot? {
    if (!snapshot.belongsToAccount(accountId) || snapshot.track?.id != mediaId) return null
    val streamUrl = uri.takeIf { candidate ->
        candidate.startsWith("file:") || trustedQMusicMediaUrl(candidate).isNotBlank()
    } ?: snapshot.streamUrl
    val streamChanged = streamUrl != snapshot.streamUrl
    return snapshot.copy(
        positionMs = positionMs,
        streamUrl = streamUrl,
        streamExpiresAt = when {
            !streamChanged -> snapshot.streamExpiresAt
            streamUrl.startsWith("file:") -> Long.MAX_VALUE
            else -> 0L
        },
    )
}

internal fun mediaButtonSkipDelta(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
    KeyEvent.KEYCODE_MEDIA_NEXT -> 1
    else -> null
}
@Suppress("DEPRECATION")
private fun mediaKeyEvent(intent: Intent): KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
} else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val skipMutex = Mutex()
    private val recoveryMutex = Mutex()
    private val recoveryStateLock = Any()
    private val recoveryTracker = PlaybackRecoveryTracker()
    private var snapshotJob: Job? = null
    private var recoveryJob: Job? = null
    private var applyingRecoveryGeneration: Long? = null
    @Volatile private var mediaItemGeneration = 0L
    private val previousCommand = SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY)
    private val nextCommand = SessionCommand(COMMAND_NEXT, Bundle.EMPTY)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            restartPendingRecovery()
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player.pause()
                persistCurrentPlayback()
            }
        }
    }
    @UnstableApi override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    require(trustedQMusicMediaUrl(chain.request().url.toString()).isNotBlank()) {
                        "playback host rejected"
                    }
                    chain.proceed(chain.request().newBuilder().withQqMusicMediaHeaders().build())
                }
                .build()
        ).setUserAgent("QMusicWatch")
        val dataSource = DefaultDataSource.Factory(this, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(QMusicLoadErrorHandlingPolicy())
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                90_000,
                2_500,
                5_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(BACKGROUND_PLAYBACK_WAKE_MODE)
            .build()
            .apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    val stopAfterCurrent = (application as? QMusicApplication)?.playback?.consumeStopAfterCurrentAtEnd() == true
                    if (!stopAfterCurrent) requestSkip(1, ended = true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    val mediaItem = player.currentMediaItem ?: return
                    val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
                    val failure = classifyPlaybackFailure(error)
                    AppLog.write(
                        "PLAYER_SERVICE",
                        "error track=${mediaItem.mediaId} type=${failure.type} retryable=${failure.retryable}",
                    )
                    if (!failure.retryable || uri.startsWith("file:")) {
                        persistCurrentPlayback()
                        return
                    }
                    scheduleRecovery(
                        PlaybackRecoveryRequest(
                            mediaId = mediaItem.mediaId,
                            failedUri = uri,
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            playWhenReady = player.playWhenReady,
                        ),
                    )
                }

                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    mediaItemGeneration++
                    val uri = mediaItem?.localConfiguration?.uri?.toString().orEmpty()
                    synchronized(recoveryStateLock) {
                        val invalidated = recoveryTracker.invalidateUnlessMatches(
                            mediaId = mediaItem?.mediaId,
                            uri = uri,
                            applyingGeneration = applyingRecoveryGeneration,
                        )
                        if (invalidated != null) recoveryJob?.cancel()
                    }
                    persistCurrentPlayback()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) persistCurrentPlayback()
                }
            })
        }
        val mediaButtons = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setSessionCommand(previousCommand).setDisplayName("上一首").setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setSessionCommand(nextCommand).setDisplayName("下一首").setSlots(CommandButton.SLOT_FORWARD).build(),
        )
        session = MediaSession.Builder(this, player).setMediaButtonPreferences(mediaButtons).setCallback(object : MediaSession.Callback {
            override fun onConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(previousCommand).add(nextCommand).build())
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .setMediaButtonPreferences(mediaButtons)
                    .build()

            override fun onCustomCommand(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = when (customCommand.customAction) {
                COMMAND_PREVIOUS -> requestSkip(-1)
                COMMAND_NEXT -> requestSkip(1)
                else -> super.onCustomCommand(mediaSession, controller, customCommand, args)
            }

            override fun onMediaButtonEvent(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
                val event = mediaKeyEvent(intent)
                val delta = event?.takeIf { it.action == KeyEvent.ACTION_DOWN && it.repeatCount == 0 }?.keyCode?.let(::mediaButtonSkipDelta) ?: return false
                requestSkip(delta)
                return true
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                serviceScope.launch {
                    runCatching {
                        val graph = application as QMusicApplication
                        val snapshot = json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first())
                        if (!snapshot.belongsToAccount(graph.vault.load()?.accountId)) error("播放记录属于其他账号")
                        val track = snapshot.track ?: error("没有可恢复的歌曲")
                        val local = snapshot.streamUrl.takeIf { it.startsWith("file:") }
                            ?.takeIf { File(android.net.Uri.parse(it).path.orEmpty()).exists() }
                        val cachedRemote = trustedQMusicMediaUrl(snapshot.streamUrl)
                        val stream = if (local != null) null else if (cachedRemote.isNotBlank() && snapshot.streamExpiresAt > System.currentTimeMillis() + 30_000) null
                        else graph.api.stream(track, snapshot.quality)
                        val uri = local ?: stream?.url ?: cachedRemote.takeIf(String::isNotBlank) ?: error("播放地址已失效")
                        if (stream != null) {
                            val owner = graph.vault.load()?.accountId
                            graph.settings.updatePlaybackSnapshot { currentValue ->
                                val latest = runCatching {
                                    json.decodeFromString<PlaybackSnapshot>(currentValue)
                                }.getOrNull()
                                if (latest?.belongsToAccount(owner) == true && latest.track?.id == track.id) {
                                    json.encodeToString(
                                        latest.copy(
                                            streamUrl = stream.url,
                                            streamExpiresAt = stream.expiresAt,
                                            quality = stream.quality,
                                        ),
                                    )
                                } else {
                                    currentValue
                                }
                            }
                        }
                        val item = playbackMediaItem(track.id, uri, track.title, track.artists.joinToString(" / "), track.artworkUrl)
                        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, snapshot.positionMs.coerceAtLeast(0))
                    }.onSuccess(result::set).onFailure { error ->
                        AppLog.write("RESUME", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
                        result.setException(error)
                    }
                }
                return result
            }
        }).build()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { AppLog.write("PLAYER_SERVICE", "network-callback ${it.javaClass.simpleName}") }
        snapshotJob = serviceScope.launch {
            while (isActive) {
                delay(BACKGROUND_SNAPSHOT_INTERVAL_MS)
                if (withContext(Dispatchers.Main.immediate) { player.currentMediaItem != null }) {
                    persistCurrentPlaybackNow()
                }
            }
        }
        AppLog.write("PLAYER_SERVICE", "created wake_mode=$BACKGROUND_PLAYBACK_WAKE_MODE")
    }

    private fun scheduleRecovery(candidate: PlaybackRecoveryRequest) {
        synchronized(recoveryStateLock) {
            startRecoveryLocked(recoveryTracker.replace(candidate))
        }
    }

    private fun restartPendingRecovery() {
        synchronized(recoveryStateLock) {
            val pending = recoveryTracker.current() ?: return
            startRecoveryLocked(recoveryTracker.replace(pending))
        }
    }

    private fun startRecoveryLocked(request: PlaybackRecoveryRequest) {
        recoveryJob?.cancel()
        recoveryJob = serviceScope.launch {
            try {
                delay(BACKGROUND_RECOVERY_DELAY_MS)
                recoverPlayback(request)
            } finally {
                val runningJob = currentCoroutineContext()[Job]
                synchronized(recoveryStateLock) {
                    if (recoveryJob === runningJob) recoveryJob = null
                }
            }
        }
    }

    private suspend fun recoverPlayback(request: PlaybackRecoveryRequest) = recoveryMutex.withLock {
        val graph = application as QMusicApplication
        var lastError: Throwable? = null
        repeat(BACKGROUND_RECOVERY_ATTEMPTS) { attempt ->
            if (!failedItemStillCurrent(request)) {
                clearRecoveryIfCurrent(request)
                return
            }
            if (attempt > 0) delay(BACKGROUND_RECOVERY_DELAY_MS * (1L shl attempt))
            try {
                val snapshot = json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first())
                val owner = graph.vault.load()?.accountId
                if (!snapshot.belongsToAccount(owner)) error("Playback snapshot owner changed")
                val track = snapshot.track?.takeIf { it.id == request.mediaId }
                    ?: snapshot.queue.firstOrNull { it.id == request.mediaId }
                    ?: error("Playback snapshot no longer contains the failed track")
                val stream = graph.api.refreshStream(track, snapshot.quality)
                if (!failedItemStillCurrent(request)) {
                    clearRecoveryIfCurrent(request)
                    return
                }
                if (!applyRecoveredStream(request, track, stream)) return
                if (!persistRecoveredPlayback(request, stream)) {
                    clearRecoveryIfCurrent(request)
                    return
                }
                if (!restoredItemStillCurrent(request, stream.url)) return
                if (!clearRecoveryIfCurrent(request)) return
                AppLog.write(
                    "PLAYER_SERVICE",
                    "recovered track=${track.id} position_ms=${request.positionMs} attempt=${attempt + 1}",
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                AppLog.write(
                    "PLAYER_SERVICE",
                    "recovery-failed track=${request.mediaId} attempt=${attempt + 1} error=${error.javaClass.simpleName}",
                )
            }
        }
        notifyRecoveryExhausted(request, lastError)
    }

    private suspend fun applyRecoveredStream(
        request: PlaybackRecoveryRequest,
        track: com.ronan.qmusicwatch.model.Track,
        stream: StreamData,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        synchronized(recoveryStateLock) {
            if (!failedItemStillCurrentOnMain(request)) return@synchronized false
            if (!recoveryTracker.expectReplacement(request, stream.url)) return@synchronized false
            applyingRecoveryGeneration = request.generation
            try {
                player.setMediaItem(
                    playbackMediaItem(
                        track.id,
                        stream.url,
                        track.title,
                        track.artists.joinToString(" / "),
                        track.artworkUrl,
                    ),
                    request.positionMs,
                )
                player.prepare()
                if (request.playWhenReady) player.play()
            } finally {
                applyingRecoveryGeneration = null
            }
            restoredItemStillCurrentOnMain(request, stream.url)
        }
    }

    private suspend fun persistRecoveredPlayback(
        request: PlaybackRecoveryRequest,
        stream: StreamData,
    ): Boolean {
        if (!restoredItemStillCurrent(request, stream.url)) return false
        val graph = application as QMusicApplication
        val owner = graph.vault.load()?.accountId
        if (!restoredItemStillCurrent(request, stream.url)) return false
        var updated = false
        graph.settings.updatePlaybackSnapshot { currentValue ->
            val latest = runCatching {
                json.decodeFromString<PlaybackSnapshot>(currentValue)
            }.getOrNull()
            val merged = latest?.let {
                mergeRecoveredPlaybackSnapshot(it, owner, request, stream)
            }
            if (merged == null) {
                currentValue
            } else {
                updated = true
                json.encodeToString(merged)
            }
        }
        return updated && restoredItemStillCurrent(request, stream.url)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun notifyRecoveryExhausted(
        request: PlaybackRecoveryRequest,
        lastError: Throwable?,
    ) = withContext(Dispatchers.Main.immediate) {
        val shouldNotify = synchronized(recoveryStateLock) {
            val stillFailed = failedItemStillCurrentOnMain(request)
            val cleared = recoveryTracker.clearIfCurrent(request)
            stillFailed && cleared
        }
        if (!shouldNotify) return@withContext
        AppLog.write(
            "PLAYER_SERVICE",
            "recovery-exhausted track=${request.mediaId} error=${lastError?.javaClass?.simpleName.orEmpty()}",
        )
        val terminalError = PlaybackException(
            PLAYBACK_RECOVERY_EXHAUSTED_MESSAGE,
            null,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )
        (application as? QMusicApplication)?.playback?.onError?.invoke(
            PlaybackErrorEvent(
                error = terminalError,
                mediaId = request.mediaId,
                positionMs = request.positionMs,
                isLocalFile = false,
            ),
        )
    }

    private fun clearRecoveryIfCurrent(request: PlaybackRecoveryRequest): Boolean =
        synchronized(recoveryStateLock) { recoveryTracker.clearIfCurrent(request) }

    private suspend fun failedItemStillCurrent(request: PlaybackRecoveryRequest): Boolean =
        withContext(Dispatchers.Main.immediate) { failedItemStillCurrentOnMain(request) }

    private fun failedItemStillCurrentOnMain(request: PlaybackRecoveryRequest): Boolean {
        if (!recoveryTracker.isCurrent(request)) return false
        val current = player.currentMediaItem ?: return false
        return current.mediaId == request.mediaId &&
            current.localConfiguration?.uri?.toString().orEmpty() == request.failedUri &&
            player.playerError != null
    }

    private suspend fun restoredItemStillCurrent(request: PlaybackRecoveryRequest, streamUrl: String): Boolean =
        withContext(Dispatchers.Main.immediate) { restoredItemStillCurrentOnMain(request, streamUrl) }

    private fun restoredItemStillCurrentOnMain(
        request: PlaybackRecoveryRequest,
        streamUrl: String,
    ): Boolean {
        if (!recoveryTracker.isCurrent(request)) return false
        val current = player.currentMediaItem ?: return false
        return current.mediaId == request.mediaId &&
            current.localConfiguration?.uri?.toString().orEmpty() == streamUrl
    }

    private fun persistCurrentPlayback() {
        if (!::player.isInitialized) return
        serviceScope.launch { persistCurrentPlaybackNow() }
    }

    private suspend fun persistCurrentPlaybackNow() {
        val current = withContext(Dispatchers.Main.immediate) {
            val item = player.currentMediaItem ?: return@withContext null
            CurrentPlayback(
                mediaId = item.mediaId,
                uri = item.localConfiguration?.uri?.toString().orEmpty(),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                generation = mediaItemGeneration,
            )
        } ?: return
        val graph = application as QMusicApplication
        if (!currentPlaybackStillCurrent(current)) return
        val owner = graph.vault.load()?.accountId
        graph.settings.updatePlaybackSnapshot { currentValue ->
            val latest = runCatching {
                json.decodeFromString<PlaybackSnapshot>(currentValue)
            }.getOrNull()
            val merged = latest?.let {
                mergePlaybackProgressSnapshot(
                    snapshot = it,
                    accountId = owner,
                    mediaId = current.mediaId,
                    uri = current.uri,
                    positionMs = current.positionMs,
                )
            }
            merged?.let { json.encodeToString(it) } ?: currentValue
        }
    }

    private suspend fun currentPlaybackStillCurrent(expected: CurrentPlayback): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val current = player.currentMediaItem ?: return@withContext false
            mediaItemGeneration == expected.generation &&
                current.mediaId == expected.mediaId &&
                current.localConfiguration?.uri?.toString().orEmpty() == expected.uri
        }

    private data class CurrentPlayback(
        val mediaId: String,
        val uri: String,
        val positionMs: Long,
        val generation: Long,
    )

    @UnstableApi
    private fun requestSkip(delta: Int, ended: Boolean = false): ListenableFuture<SessionResult> = SettableFuture.create<SessionResult>().also { result ->
        serviceScope.launch {
            runCatching { skipMutex.withLock { skipFromSnapshot(delta, ended) } }
                .onSuccess { changed ->
                    result.set(
                        if (changed) SessionResult(SessionResult.RESULT_SUCCESS)
                        else SessionResult(SessionError.INFO_CANCELLED),
                    )
                }
                .onFailure { error ->
                    AppLog.write("MEDIA_KEY", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
                    result.set(SessionResult(SessionError.ERROR_IO))
                }
        }
    }

    private suspend fun skipFromSnapshot(delta: Int, ended: Boolean): Boolean {
        val graph = application as QMusicApplication
        val owner = graph.vault.load()?.accountId ?: error("请先登录")
        val snapshot = json.decodeFromString<PlaybackSnapshot>(graph.settings.playbackSnapshot.first())
        if (!snapshot.belongsToAccount(owner)) error("播放记录属于其他账号")
        val queue = snapshot.queue.distinctBy { it.id }
        if (queue.isEmpty()) return false
        val currentId = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId } ?: snapshot.track?.id
        val current = queue.indexOfFirst { it.id == currentId }
        val mode = graph.settings.playMode.first()
        val shuffled = if (mode == "shuffle" && queue.size > 1) queue.indices.filter { it != current }.random() else -1
        val targetIndex = nextQueueIndex(queue.size, current, delta, mode, ended = ended, shuffled = shuffled)
        val track = queue.getOrNull(targetIndex) ?: return false
        val local = graph.db.downloads().find(track.id, owner)?.takeIf { it.status == "complete" && File(it.filePath).exists() }
        val preferredQuality = graph.settings.quality.first()
        val stream = if (local == null) graph.api.stream(track, preferredQuality) else null
        val uri = local?.let { android.net.Uri.fromFile(File(it.filePath)).toString() } ?: stream!!.url
        val artwork = local?.let { cachedArtworkFile(it.filePath).takeIf(File::exists)?.let { cover -> android.net.Uri.fromFile(cover).toString() } } ?: track.artworkUrl
        graph.settings.setPlaybackSnapshot(json.encodeToString(snapshot.copy(
            track = track, positionMs = 0, streamUrl = uri,
            streamExpiresAt = stream?.expiresAt ?: Long.MAX_VALUE, quality = stream?.quality ?: preferredQuality,
        )))
        graph.db.recent().upsert(RecentEntity(track.id, owner, track.title, track.artists.joinToString(" / "), track.album, track.artworkUrl, System.currentTimeMillis()))
        withContext(Dispatchers.Main) {
            player.setMediaItem(playbackMediaItem(track.id, uri, track.title, track.artists.joinToString(" / "), artwork))
            player.prepare()
            player.play()
        }
        return true
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.write(
            "PLAYER",
            "task-removed playing=${player.isPlaying} playWhenReady=${player.playWhenReady} state=${player.playbackState}",
        )
        persistCurrentPlayback()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        AppLog.write("PLAYER_SERVICE", "destroyed playing=${player.isPlaying}")
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        snapshotJob?.cancel()
        recoveryJob?.cancel()
        serviceScope.cancel()
        unregisterReceiver(noisy)
        session?.release()
        player.release()
        super.onDestroy()
    }
}
