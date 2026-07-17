package com.ronan.qmusicwatch.playback

import android.content.*
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
import com.ronan.qmusicwatch.model.belongsToAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val COMMAND_PREVIOUS = "com.ronan.qmusicwatch.PREVIOUS"
private const val COMMAND_NEXT = "com.ronan.qmusicwatch.NEXT"
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
    private val previousCommand = SessionCommand(COMMAND_PREVIOUS, Bundle.EMPTY)
    private val nextCommand = SessionCommand(COMMAND_NEXT, Bundle.EMPTY)
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause() }
    }
    @UnstableApi override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(OkHttpClient()).setUserAgent("QQMusic 14090008 Android")
            .setDefaultRequestProperties(mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com"))
        val dataSource = DefaultDataSource.Factory(this, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    val stopAfterCurrent = (application as? QMusicApplication)?.playback?.consumeStopAfterCurrentAtEnd() == true
                    if (!stopAfterCurrent) requestSkip(1, ended = true)
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
                        val stream = if (local != null) null else if (snapshot.streamUrl.isNotBlank() && snapshot.streamExpiresAt > System.currentTimeMillis() + 30_000) null
                        else graph.api.stream(track, snapshot.quality)
                        val uri = local ?: stream?.url ?: snapshot.streamUrl.takeIf(String::isNotBlank) ?: error("播放地址已失效")
                        if (stream != null) graph.settings.setPlaybackSnapshot(json.encodeToString(snapshot.copy(streamUrl = stream.url, streamExpiresAt = stream.expiresAt, quality = stream.quality)))
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
    }

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
    override fun onDestroy() { serviceScope.cancel(); unregisterReceiver(noisy); session?.release(); player.release(); super.onDestroy() }
}
