package com.ronan.qmusicwatch

import android.os.Looper
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.time.Duration
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ronan.qmusicwatch.data.DownloadEntity
import com.ronan.qmusicwatch.model.SessionTokens
import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.PlaybackSnapshot
import com.ronan.qmusicwatch.model.CollectionDetail
import com.ronan.qmusicwatch.model.CachedAccountSnapshot
import com.ronan.qmusicwatch.model.CachedAccountSnapshots
import com.ronan.qmusicwatch.model.LibraryData
import com.ronan.qmusicwatch.model.MusicCollection
import com.ronan.qmusicwatch.lyrics.LyricLine
import com.ronan.qmusicwatch.network.ControlPlaneClient
import com.ronan.qmusicwatch.update.UpdateManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContextImpl

/** These tests inspect requests and settings, not the Media3 Binder protocol. */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class SettingsTestServiceBinding : ShadowContextImpl() {
    @Implementation
    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean = false

    @Implementation(minSdk = 29)
    override fun bindService(intent: Intent, flags: Int, executor: Executor, connection: ServiceConnection): Boolean = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QMusicApplication::class, shadows = [SettingsTestServiceBinding::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSettingsRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication() as QMusicApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val streamRequests = CopyOnWriteArrayList<String>()
    @Volatile private var requestHook: ((String) -> Unit)? = null
    @Volatile private var responseData: ((String) -> String?)? = null
    // Deliberately minimal quality metadata: old lists can omit SQ support.
    private val track = Track("00485V8K4InqbZ", "Song", artists = listOf("Artist"), mediaMid = "media-mid")

    private fun setFixtureField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Before fun setUp(): Unit = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        // In-memory synthetic session only; never access AndroidKeyStore or
        // contact QQ Music, the control plane, or any real user's account.
        setFixtureField(app.vault, "cached", SessionTokens(accountId = "test-owner", upstreamCookie = "qqmusic_uin=12345; qm_keyst=test-only"))
        setFixtureField(app.vault, "cacheValid", true)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().apply(it::writeTo).readUtf8() }.orEmpty()
            requestHook?.invoke(body)
            val isStream = body.contains("CgiGetVkey") || body.contains("UrlGetVkey")
            if (isStream) streamRequests += body
            val data = responseData?.invoke(body) ?: if (isStream) {
                """{"midurlinfo":[{"purl":"F000media-mid.flac","filename":"F000media-mid.flac"}],"sip":["https://dl.stream.qqmusic.qq.com/"],"expiration":3600}"""
            } else """{"songlist":[],"lyric":"","trans":""}"""
            val payload = if (request.url.host == "heyboxlite.xyz") {
                """{"ok":false,"error":{"code":"TEST_STUB","message":"test only"}}"""
            } else """{"code":0,"req_0":{"code":0,"data":$data}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.host == "heyboxlite.xyz") 503 else 200).message("Test fixture")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        setFixtureField(app.api, "http", http)
        app.controlPlane = ControlPlaneClient(http = http)
        app.updates = UpdateManager(app, app.controlPlane)
        app.settings.setQuality("sq")
        app.settings.setWifiOnlyDownload(false)
        app.settings.setHeadphoneWarning(false)
        app.settings.setRotaryVolumeEnabled(true)
        app.settings.setRotaryReversed(false)
        app.settings.setRotaryVolumeKeysScroll(false)
        app.settings.setPlaybackSnapshot("")
        app.settings.setAccountSnapshots("")
    }

    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        app.playback.release()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        app.db.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }

    private suspend fun viewModel(expectedQuality: String = "sq"): AppViewModel {
        val vm = AppViewModel(app)
        stores += ViewModelStore().also { it.put("settings-test", vm) }
        awaitCondition { vm.state.value.sessionLoaded && vm.settings.value.quality == expectedQuality }
        return vm
    }

    private fun work(id: String = track.id) = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork("download-test-owner-$id").get().first { !it.state.isFinished }

    @Suppress("UNCHECKED_CAST")
    private fun uiState(vm: AppViewModel) = AppViewModel::class.java.getDeclaredField("_state")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppUiState>

    private suspend fun savedPlayback() = Json.decodeFromString<PlaybackSnapshot>(app.settings.playbackSnapshot.first())

    @Test fun savedDefaultsRemainCurrentWithoutIndividualUiCollectors() = runBlocking {
        val vm = viewModel()
        awaitCondition { vm.quality.value == "sq" && !vm.wifiOnlyDownload.value }
        vm.setQuality("hq")
        val changed = vm.setWifiOnlyDownload(true)
        awaitCondition { changed.isCompleted }
        changed.join()
        awaitCondition { vm.settings.value.quality == "hq" && vm.quality.value == "hq" && vm.wifiOnlyDownload.value }
    }

    @Test fun rotaryCompatibilitySettingsPersistWithoutChangingExistingDefaults() = runBlocking {
        val vm = viewModel()
        assertTrue(vm.settings.value.rotaryVolumeEnabled)
        assertFalse(vm.settings.value.rotaryReversed)
        assertFalse(vm.settings.value.rotaryVolumeKeysScroll)
        vm.setRotaryReversed(true)
        vm.setRotaryVolumeKeysScroll(true)
        vm.setRotaryVolumeEnabled(false)
        awaitCondition { vm.settings.value.rotaryReversed && vm.settings.value.rotaryVolumeKeysScroll && !vm.settings.value.rotaryVolumeEnabled }
        val stored = app.settings.snapshot.first()
        assertTrue(stored.rotaryReversed)
        assertTrue(stored.rotaryVolumeKeysScroll)
        assertFalse(stored.rotaryVolumeEnabled)
        assertEquals("sq", stored.quality)
    }

    @Test fun volumeTurnsNeverQueueBehindAPendingControllerConnection() {
        val backing = SettableFuture.create<MediaController>()
        var queued = 0
        val pending = object : ListenableFuture<MediaController> by backing {
            override fun addListener(listener: Runnable, executor: Executor) {
                queued++
                backing.addListener(listener, executor)
            }
        }
        setFixtureField(app.playback, "future", pending)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(app.playback.adjustVolume(1))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse("Rapid duplicate events must remain throttled", app.playback.adjustVolume(1))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertTrue(app.playback.adjustVolume(-1))
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("No delayed volume operations may be registered", 0, queued)
        assertFalse(backing.isDone)
    }

    @Test
    @Config(shadows = [RecordingAudioManager::class])
    fun touchVolumeAndLiveReadbackWorkWithCrownVolumeDisabled() = runBlocking {
        app.settings.setRotaryVolumeEnabled(false)
        val vm = viewModel()
        awaitCondition { !vm.settings.value.rotaryVolumeEnabled }
        setFixtureField(app.playback, "future", SettableFuture.create<MediaController>())
        val audio = app.getSystemService(AudioManager::class.java)
        val audioCalls = shadowOf(audio) as RecordingAudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        vm.refreshDeviceVolume()
        assertEquals(2, vm.deviceVolume.value!!.current)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(vm.adjustVolume(1, showSystemUi = false))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(3, vm.deviceVolume.value!!.current)
        // Robolectric 4.16 adjusts all shadow streams via its one-argument
        // setter. Check the requested stream/flags instead of that shadow bug.
        assertEquals(AudioManager.STREAM_MUSIC, audioCalls.lastAdjustedStream)
        assertEquals(0, audioCalls.lastAdjustmentFlags)

        // A headset/system change must be read back rather than keeping an
        // optimistic percentage or restoring an earlier absolute volume.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        vm.refreshDeviceVolume()
        assertEquals(1, vm.deviceVolume.value!!.current)
        assertFalse(vm.settings.value.rotaryVolumeEnabled)
    }

    @Implements(AudioManager::class)
    class RecordingAudioManager : ShadowAudioManager() {
        var lastAdjustedStream: Int = -1
        var lastAdjustmentFlags: Int = -1

        @Implementation
        public override fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {
            lastAdjustedStream = streamType
            lastAdjustmentFlags = flags
            super.adjustStreamVolume(streamType, direction, flags)
        }
    }

    @Test fun playbackRequestsPersistedSqWithoutAQualityCollectorOrCompleteTrackMetadata() = runBlocking {
        val vm = viewModel()
        vm.requestPlay(track, allowSpeaker = true)
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        assertTrue(streamRequests.isNotEmpty())
        assertTrue("First stream request must use SQ", streamRequests.first().contains("F000media-mid.flac"))
        assertEquals("sq", vm.state.value.activeStreamQuality)
    }

    @Test fun changingTheDefaultAlsoRequestsSqForTheCurrentMinimalMetadataTrack() = runBlocking {
        app.settings.setQuality("standard")
        val vm = viewModel("standard")
        vm.requestPlay(track, allowSpeaker = true)
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        assertTrue(streamRequests.first().contains("M500media-mid.mp3"))

        val initialRequests = streamRequests.size
        vm.setQuality("sq")
        awaitCondition { streamRequests.size > initialRequests && vm.quality.value == "sq" }
        assertTrue(streamRequests.last().contains("F000media-mid.flac"))
    }

    @Test fun existingStandardCacheIsPlayedAndReportedAtItsActualQuality() = runBlocking {
        val audio = File(app.filesDir, "test-offline.audio").apply { writeBytes(byteArrayOf(0, 1)) }
        app.db.downloads().upsert(DownloadEntity(
            track.id, "test-owner", track.title, "Artist", "", audio.absolutePath,
            "complete", audio.length(), audio.length(), quality = "standard",
        ))
        val vm = viewModel()
        vm.requestPlay(track, allowSpeaker = true)
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        assertEquals("standard", vm.state.value.activeStreamQuality)
        assertTrue(streamRequests.isEmpty())
    }

    @Test fun cachingAndRetryUseMobilePolicyAndKeepTheRequestedQuality() = runBlocking {
        val vm = viewModel()
        vm.cache(track)
        awaitCondition { app.db.downloads().find(track.id, "test-owner") != null && vm.state.value.message == "已加入离线缓存" }
        assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)
        assertEquals("sq", app.db.downloads().find(track.id, "test-owner")!!.quality)

        val row = app.db.downloads().find(track.id, "test-owner")!!
        app.db.downloads().progress(track.id, "test-owner", "paused", 0, -1)
        app.settings.setQuality("standard")
        vm.resumeDownload(row.copy(status = "paused"))
        awaitCondition { app.db.downloads().find(track.id, "test-owner")!!.status == "queued" }
        assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)
        assertEquals("sq", app.db.downloads().find(track.id, "test-owner")!!.quality)
    }

    @Test fun disablingWifiInSettingsReleasesAnAlreadyWaitingTask() = runBlocking {
        app.settings.setWifiOnlyDownload(true)
        val vm = viewModel()
        app.downloads.enqueue(track, "test-owner", "sq", true, "Liked")
        val id = work().id
        val changed = vm.setWifiOnlyDownload(false)
        awaitCondition { changed.isCompleted }
        changed.join()
        assertEquals(id, work().id)
        assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)
        assertEquals("queued", app.db.downloads().find(track.id, "test-owner")!!.status)
    }

    @Test fun startupRepairsOldWifiConstraintsWhenTheSavedSwitchIsAlreadyOff() = runBlocking {
        app.downloads.enqueue(track, "test-owner", "sq", true, "Liked")
        val id = work().id
        viewModel()
        awaitCondition { app.db.downloads().find(track.id, "test-owner")!!.status == "queued" }
        assertEquals(id, work().id)
        assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)
    }

    @Test fun foregroundSkipUsesSavedLoopModeWithoutAnIndividualCollector() = runBlocking {
        val other = track.copy(id = "003OUlho2HcRHC", title = "Other")
        app.settings.setPlayMode("loop_all")
        app.settings.setPlaybackSnapshot(Json.encodeToString(PlaybackSnapshot(
            track = other, queue = listOf(track, other), ownerAccountId = "test-owner",
        )))
        val vm = viewModel()
        awaitCondition { vm.state.value.currentTrack?.id == other.id }
        vm.skipNext()
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        assertEquals("loop_all", vm.settings.value.playMode)
    }

    @Test fun selectingAnotherTrackDoesNotInheritTheRestoredPosition() = runBlocking {
        app.settings.setPlaybackSnapshot(Json.encodeToString(PlaybackSnapshot(
            track = track, queue = listOf(track), positionMs = 42_000, ownerAccountId = "test-owner",
        )))
        val vm = viewModel()
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        val other = track.copy(id = "003OUlho2HcRHC", title = "Other")
        vm.requestPlay(other, allowSpeaker = true)
        awaitCondition { savedPlayback().track?.id == other.id }
        assertEquals(0L, savedPlayback().positionMs)
    }

    @Test fun savingAnIdleRestoredPlayerDoesNotEraseItsPosition() = runBlocking {
        app.settings.setPlaybackSnapshot(Json.encodeToString(PlaybackSnapshot(
            track = track, queue = listOf(track), positionMs = 42_000, ownerAccountId = "test-owner",
        )))
        val vm = viewModel()
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        val save = vm.savePlaybackState()
        awaitCondition { save.isCompleted }
        save.join()
        assertEquals(42_000L, savedPlayback().positionMs)
        vm.requestPlay(track, allowSpeaker = true)
        awaitCondition { savedPlayback().streamUrl.isNotBlank() }
        assertEquals(42_000L, savedPlayback().positionMs)
    }

    @Test fun consumingAnOldSnackbarDoesNotClearANewerMessage() = runBlocking {
        val vm = viewModel()
        uiState(vm).value = vm.state.value.copy(message = "new message")
        vm.consumeMessage("old message")
        assertEquals("new message", vm.state.value.message)
        vm.consumeMessage("new message")
        assertNull(vm.state.value.message)
    }

    @Test fun failedLibraryRefreshPreservesTheAccountSnapshotAndExistingPlaylists() = runBlocking {
        val library = LibraryData(listOf(track), listOf(MusicCollection("9988", "收藏歌单", owned = false)))
        val saved = Json.encodeToString(CachedAccountSnapshots(listOf(
            CachedAccountSnapshot("test-owner", library = library, updatedAt = 100L),
        )))
        app.settings.setAccountSnapshots(saved)
        responseData = { body ->
            if (body.contains("GetPlaylistFavInfo")) throw java.io.IOException("synthetic favorite failure")
            """{"v_playlist":[],"songlist":[]}"""
        }
        val vm = viewModel()
        val refresh = vm.loadLibrary()
        awaitCondition { refresh.isCompleted }
        refresh.join()
        assertEquals(library, vm.state.value.library)
        assertNotNull(vm.state.value.libraryError)
        assertFalse(vm.state.value.libraryLoading)
        assertTrue(vm.state.value.offlineSnapshot)
        assertEquals(saved, app.settings.accountSnapshots.first())
    }

    @Test fun initialLibraryFailureIsNotEmptySuccessAndCanBeRetried() = runBlocking {
        responseData = { body ->
            if (body.contains("GetPlaylistFavInfo")) throw java.io.IOException("synthetic favorite failure")
            """{"v_playlist":[],"songlist":[]}"""
        }
        val vm = viewModel()
        val failed = vm.loadLibrary()
        awaitCondition { failed.isCompleted }
        failed.join()
        assertNull(vm.state.value.library)
        assertNotNull(vm.state.value.libraryError)
        assertFalse(vm.state.value.libraryLoading)

        responseData = { body ->
            if (body.contains("GetPlaylistFavInfo")) """{"v_list":[{"tid":"9988","dissname":"重新读取的歌单","songnum":42}]}"""
            else """{"v_playlist":[],"songlist":[]}"""
        }
        val retry = vm.loadLibrary()
        awaitCondition { retry.isCompleted }
        retry.join()
        assertNull(vm.state.value.libraryError)
        assertFalse(vm.state.value.libraryLoading)
        assertFalse(vm.state.value.offlineSnapshot)
        assertEquals(listOf("9988"), vm.state.value.library!!.playlists.map(MusicCollection::id))
        val saved = Json.decodeFromString<CachedAccountSnapshots>(app.settings.accountSnapshots.first())
        assertEquals(vm.state.value.library, saved.items.single { it.accountId == "test-owner" }.library)
    }

    @Test fun repeatedLibraryRefreshReusesTheInFlightRequest() = runBlocking {
        val vm = viewModel()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        requestHook = { body ->
            if (body.contains("GetPlaylistFavInfo")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        responseData = { """{"v_playlist":[],"v_list":[],"songlist":[]}""" }
        val first = vm.loadLibrary()
        try {
            awaitCondition { entered.count == 0L }
            assertTrue(vm.state.value.libraryLoading)
            assertSame(first, vm.loadLibrary())
        } finally { release.countDown() }
        awaitCondition { first.isCompleted }
        first.join()
        assertFalse(vm.state.value.libraryLoading)
        assertNull(vm.state.value.libraryError)
    }

    @Test fun removingATrackAlsoUpdatesTheDeclaredPlaylistCount() = runBlocking {
        val vm = viewModel()
        val song = track.copy(numericId = 123)
        uiState(vm).value = vm.state.value.copy(
            detail = CollectionDetail("歌单", listOf(song), trackCount = 400), detailDirectoryId = "101",
        )
        val remove = vm.removeFromPlaylist(song, "101")
        awaitCondition { remove.isCompleted }
        remove.join()
        assertEquals(399, vm.state.value.detail!!.trackCount)
        assertTrue(vm.state.value.detail!!.tracks.isEmpty())
    }

    @Test fun aLateLyricsReloadCannotReplaceTheNewTracksLyrics() = runBlocking {
        val vm = viewModel()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        requestHook = { body ->
            if (body.contains("GetPlayLyricInfo") && body.contains("\"crypt\":0")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        responseData = { body -> if (body.contains("GetPlayLyricInfo")) """{"lyric":"[00:01]Old lyric","trans":""}""" else null }
        uiState(vm).value = vm.state.value.copy(currentTrack = track)
        val reload = vm.reloadLyrics()
        try {
            awaitCondition { entered.count == 0L }
            val other = track.copy(id = "003OUlho2HcRHC")
            val currentLyrics = listOf(LyricLine(1_000, "New lyric"))
            uiState(vm).value = vm.state.value.copy(currentTrack = other, lyrics = currentLyrics)
            release.countDown()
            awaitCondition { reload.isCompleted }
            reload.join()
            assertEquals(currentLyrics, vm.state.value.lyrics)
            assertEquals(other.id, vm.state.value.currentTrack?.id)
        } finally { release.countDown() }
    }

    @Test fun aLatePlaylistRemovalCannotRemoveSongsFromAnotherOpenPlaylist() = runBlocking {
        val vm = viewModel()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        requestHook = { body ->
            if (body.contains("DelSonglist")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val song = track.copy(numericId = 123)
        uiState(vm).value = vm.state.value.copy(detail = CollectionDetail("A", listOf(song)), detailDirectoryId = "101")
        val remove = vm.removeFromPlaylist(song, "101")
        try {
            awaitCondition { entered.count == 0L }
            val otherDetail = CollectionDetail("B", listOf(song, song.copy(id = "003OUlho2HcRHC")))
            uiState(vm).value = vm.state.value.copy(detail = otherDetail, detailDirectoryId = "202")
            release.countDown()
            awaitCondition { remove.isCompleted }
            remove.join()
            assertEquals(otherDetail, vm.state.value.detail)
        } finally { release.countDown() }
    }

    @Test fun wifiSettingCannotOvertakeAnEnqueueUsingThePreviousPolicy() = runBlocking {
        app.settings.setWifiOnlyDownload(true)
        val vm = viewModel()
        val scheduling = app.downloads.javaClass.getDeclaredField("schedulingMutex")
            .apply { isAccessible = true }.get(app.downloads) as Mutex
        val policy = AppViewModel::class.java.getDeclaredField("downloadPolicyMutex")
            .apply { isAccessible = true }.get(vm) as Mutex
        awaitCondition { !policy.isLocked }
        scheduling.lock()
        var released = false
        try {
            vm.cache(track)
            awaitCondition { policy.isLocked }
            val changed = vm.setWifiOnlyDownload(false)
            shadowOf(Looper.getMainLooper()).idle()
            delay(50)
            assertTrue(app.settings.wifiOnlyDownload.first())
            scheduling.unlock()
            released = true
            awaitCondition { changed.isCompleted }
            changed.join()
            assertEquals(NetworkType.CONNECTED, work().constraints.requiredNetworkType)
            assertFalse(app.settings.wifiOnlyDownload.first())
        } finally { if (!released) scheduling.unlock() }
    }

    @Test fun logoutClearsCancelledPlaybackAndDetailLoadingStates() = runBlocking {
        val vm = viewModel()
        uiState(vm).value = vm.state.value.copy(playbackLoading = true, detailLoading = true, detailError = "old error")
        vm.logout()
        assertFalse(vm.state.value.playbackLoading)
        assertFalse(vm.state.value.detailLoading)
        assertNull(vm.state.value.detailError)
        assertNull(vm.state.value.currentTrack)
    }

    @Test fun anOldQueuedSnapshotCannotOverwriteANewTrackAndPosition() = runBlocking {
        val original = PlaybackSnapshot(track = track, queue = listOf(track), positionMs = 42_000, ownerAccountId = "test-owner")
        app.settings.setPlaybackSnapshot(Json.encodeToString(original))
        val vm = viewModel()
        awaitCondition { vm.state.value.currentTrack?.id == track.id }
        val snapshotMutex = AppViewModel::class.java.getDeclaredField("playbackSnapshotMutex")
            .apply { isAccessible = true }.get(vm) as Mutex
        snapshotMutex.lock()
        var released = false
        try {
            val oldSave = vm.savePlaybackState()
            val other = track.copy(id = "003OUlho2HcRHC")
            val latest = original.copy(track = other, queue = listOf(other), positionMs = 9_000)
            uiState(vm).value = vm.state.value.copy(currentTrack = other)
            app.settings.setPlaybackSnapshot(Json.encodeToString(latest))
            snapshotMutex.unlock()
            released = true
            awaitCondition { oldSave.isCompleted }
            oldSave.join()
            assertEquals(latest, savedPlayback())
        } finally { if (!released) snapshotMutex.unlock() }
    }

    @Test fun logoutCancelsAnOldAccountsCacheRequestWaitingForThePolicyLock() = runBlocking {
        val vm = viewModel()
        val policy = AppViewModel::class.java.getDeclaredField("downloadPolicyMutex")
            .apply { isAccessible = true }.get(vm) as Mutex
        awaitCondition { !policy.isLocked }
        policy.lock()
        try {
            vm.cache(track)
            vm.logout()
        } finally { policy.unlock() }
        shadowOf(Looper.getMainLooper()).idle()
        delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(app.db.downloads().find(track.id, "test-owner"))
    }
}
