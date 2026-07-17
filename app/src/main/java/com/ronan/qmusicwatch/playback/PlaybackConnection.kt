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
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    var onError: ((PlaybackErrorEvent) -> Unit)? = null
    var onMediaItemChanged: ((String, String) -> Unit)? = null
    init {
        future.addListener({
            controllerOrNull()?.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val causes = generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }
                    AppLog.write("PLAYER", "${error.errorCodeName} $causes")
                    val controller = controllerOrNull() ?: return
                    onError?.invoke(PlaybackErrorEvent(
                        error = error,
                        mediaId = controller.currentMediaItem?.mediaId.orEmpty(),
                        positionMs = controller.currentPosition.coerceAtLeast(0),
                        isLocalFile = controller.currentMediaItem?.localConfiguration?.uri?.scheme == "file",
                    ))
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem ?: return
                    onMediaItemChanged?.invoke(mediaItem.mediaId, mediaItem.localConfiguration?.uri?.toString().orEmpty())
                }
            }) ?: AppLog.write("PLAYER", "controller connection failed")
        }, mainExecutor)
    }
    private fun controllerOrNull(): MediaController? =
        if (!future.isDone || future.isCancelled) null else runCatching { future.get() }
            .onFailure { AppLog.write("PLAYER", "controller ${it.javaClass.simpleName}:${it.message.orEmpty()}") }
            .getOrNull()

    private fun withController(action: (MediaController) -> Unit) {
        val run = { controllerOrNull()?.let { controller -> runCatching { action(controller) }.onFailure { AppLog.write("PLAYER", "action ${it.javaClass.simpleName}:${it.message.orEmpty()}") } }; Unit }
        if (future.isDone) run() else future.addListener(run, mainExecutor)
    }
    fun play(id: String, uri: String, title: String, artist: String, artwork: String) {
        AppLog.write("PLAYER", "prepare track=$id scheme=${android.net.Uri.parse(uri).scheme.orEmpty()}")
        withController {
            it.apply {
            setMediaItem(playbackMediaItem(id, uri, title, artist, artwork))
            prepare(); play()
            }
        }
    }
    fun pause() = withController(MediaController::pause)
    fun stopAndClear() {
        cancelSleepTimer()
        withController { controller -> controller.stop(); controller.clearMediaItems() }
    }
    fun resume() = withController(MediaController::play)
    fun seek(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun position() = controllerOrNull()?.currentPosition?.coerceAtLeast(0) ?: 0L
    fun duration() = controllerOrNull()?.duration?.coerceAtLeast(0) ?: 0L
    fun currentMediaId() = controllerOrNull()?.currentMediaItem?.mediaId.orEmpty()
    fun currentUri() = controllerOrNull()?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
    fun isPlaying() = controllerOrNull()?.isPlaying == true
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
                if (!finishCurrent && _sleepRemaining.value <= 10) {
                    val player = controllerOrNull() ?: continue
                    val initial = sleepVolume ?: player.volume.also { sleepVolume = it }
                    player.volume = initial * (_sleepRemaining.value / 10f)
                }
            }
            if (finishCurrent) stopAfterCurrent = true else {
                controllerOrNull()?.let { player ->
                    pause(); player.volume = sleepVolume ?: player.volume; sleepVolume = null
                } ?: pause()
            }
        }
    }
    fun cancelSleepTimer() { sleepJob?.cancel(); sleepJob = null; stopAfterCurrent = false; sleepVolume?.let { volume -> controllerOrNull()?.volume = volume }; sleepVolume = null; _sleepRemaining.value = 0 }
    fun consumeStopAfterCurrentAtEnd(): Boolean {
        if (!stopAfterCurrent) return false
        stopAfterCurrent = false
        cancelSleepTimer()
        pause()
        return true
    }
    fun release() { scope.cancel(); MediaController.releaseFuture(future) }
}

internal fun playbackMediaItem(id: String, uri: String, title: String, artist: String, artwork: String) =
    MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .apply { if (artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(artwork)) }.build()
    ).build()
