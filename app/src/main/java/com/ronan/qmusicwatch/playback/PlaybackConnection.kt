package com.ronan.qmusicwatch.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.ronan.qmusicwatch.data.AppLog

class PlaybackConnection(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val future: ListenableFuture<MediaController> = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _sleepRemaining = MutableStateFlow(0L)
    val sleepRemaining = _sleepRemaining.asStateFlow()
    private var sleepJob: Job? = null
    private var stopAfterCurrent = false
    private var sleepVolume: Float? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    init {
        future.addListener({
            future.get().addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_ENDED) return
                    if (stopAfterCurrent) { stopAfterCurrent = false; cancelSleepTimer(); pause() } else onEnded?.invoke()
                }
                override fun onPlayerError(error: PlaybackException) {
                    val causes = generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }
                    AppLog.write("PLAYER", "${error.errorCodeName} $causes")
                    onError?.invoke("播放失败：${error.errorCodeName}")
                }
            })
        }, ContextCompat.getMainExecutor(context))
    }
    fun play(id: String, uri: String, title: String, artist: String, artwork: String) {
        AppLog.write("PLAYER", "prepare track=$id scheme=${android.net.Uri.parse(uri).scheme.orEmpty()}")
        future.get().apply {
            setMediaItem(MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).apply { if (artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(artwork)) }.build()).build())
            prepare(); play()
        }
    }
    fun pause() { if (future.isDone) future.get().pause() }
    fun resume() { if (future.isDone) future.get().play() }
    fun seek(positionMs: Long) { if (future.isDone) future.get().seekTo(positionMs) }
    fun position() = if (future.isDone) future.get().currentPosition.coerceAtLeast(0) else 0L
    fun duration() = if (future.isDone) future.get().duration.coerceAtLeast(0) else 0L
    fun isPlaying() = future.isDone && future.get().isPlaying
    fun adjustVolume(direction: Int) = audio.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        AudioManager.FLAG_SHOW_UI,
    )
    fun startSleepTimer(minutes: Int, finishCurrent: Boolean = false) {
        sleepJob?.cancel()
        stopAfterCurrent = false
        sleepVolume = null
        sleepJob = scope.launch {
            _sleepRemaining.value = minutes.coerceIn(1, 1440) * 60L
            while (_sleepRemaining.value > 0) {
                delay(1_000); _sleepRemaining.value--
                if (!finishCurrent && _sleepRemaining.value <= 10 && future.isDone) {
                    val player = future.get()
                    val initial = sleepVolume ?: player.volume.also { sleepVolume = it }
                    player.volume = initial * (_sleepRemaining.value / 10f)
                }
            }
            if (finishCurrent) stopAfterCurrent = true else {
                if (future.isDone) {
                    val player = future.get()
                    pause(); player.volume = sleepVolume ?: player.volume; sleepVolume = null
                } else pause()
            }
        }
    }
    fun cancelSleepTimer() { sleepJob?.cancel(); sleepJob = null; stopAfterCurrent = false; if (future.isDone) sleepVolume?.let { future.get().volume = it }; sleepVolume = null; _sleepRemaining.value = 0 }
    fun release() { scope.cancel(); MediaController.releaseFuture(future) }
}
