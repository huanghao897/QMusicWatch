package com.ronan.qmusicwatch.playback

import android.content.*
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ronan.qmusicwatch.QMusicApplication
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import com.ronan.qmusicwatch.model.belongsToAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
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
        }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
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
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() { serviceScope.cancel(); unregisterReceiver(noisy); session?.release(); player.release(); super.onDestroy() }
}
