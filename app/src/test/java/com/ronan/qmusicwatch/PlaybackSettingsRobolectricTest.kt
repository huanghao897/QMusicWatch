package com.ronan.qmusicwatch

import android.os.Looper
import android.content.Intent
import android.content.ServiceConnection
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ronan.qmusicwatch.data.DownloadEntity
import com.ronan.qmusicwatch.model.SessionTokens
import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.ControlPlaneClient
import com.ronan.qmusicwatch.update.UpdateManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.io.File
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
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
            val isStream = body.contains("CgiGetVkey") || body.contains("UrlGetVkey")
            if (isStream) streamRequests += body
            val data = if (isStream) {
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
        app.settings.setPlaybackSnapshot("")
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

    @Test fun savedDefaultsRemainCurrentWithoutIndividualUiCollectors() = runBlocking {
        val vm = viewModel()
        awaitCondition { vm.quality.value == "sq" && !vm.wifiOnlyDownload.value }
        vm.setQuality("hq")
        val changed = vm.setWifiOnlyDownload(true)
        awaitCondition { changed.isCompleted }
        changed.join()
        awaitCondition { vm.settings.value.quality == "hq" && vm.quality.value == "hq" && vm.wifiOnlyDownload.value }
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
}
