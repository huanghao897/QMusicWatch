package com.ronan.qmusicwatch

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ronan.qmusicwatch.data.AppDatabase
import com.ronan.qmusicwatch.download.DownloadController
import com.ronan.qmusicwatch.download.downloadWorkRequest
import com.ronan.qmusicwatch.model.Track
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class DownloadPolicyRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private lateinit var db: AppDatabase
    private lateinit var controller: DownloadController
    private val manager get() = WorkManager.getInstance(context)
    private val track = Track("00485V8K4InqbZ", "Song", artists = listOf("Artist"), mediaMid = "media-mid", numericId = 123)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        controller = DownloadController(context, db)
    }

    @After fun tearDown() {
        manager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }

    private fun work(owner: String = "owner", id: String = track.id) =
        manager.getWorkInfosForUniqueWork("download-$owner-$id").get().first { !it.state.isFinished }

    @Test fun mobileDownloadRequestKeepsSelectedQualityAndTrackMetadata() {
        val request = downloadWorkRequest(track, "owner", "lossless", false, "Liked")
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals("sq", request.workSpec.input.getString("quality"))
        assertEquals("media-mid", request.workSpec.input.getString("mediaMid"))
        assertEquals(123L, request.workSpec.input.getLong("numericId", 0))
        assertEquals("Liked", request.workSpec.input.getString("group"))
    }

    @Test fun disablingWifiUpdatesExistingWorkInPlaceAndPreservesPartialDownload() = runBlocking {
        controller.enqueue(track, "owner", "sq", true, "Liked")
        val original = work()
        val stored = db.downloads().find(track.id, "owner")!!
        val part = File(stored.filePath.removeSuffix(".audio") + ".part")
        part.parentFile!!.mkdirs()
        part.writeText("partial")
        db.downloads().progress(track.id, "owner", "queued_wifi", part.length(), 999)

        controller.updateQueuedNetworkPolicy("owner", false)

        val updated = work()
        assertEquals(original.id, updated.id)
        assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
        assertTrue(updated.generation > original.generation)
        val row = db.downloads().find(track.id, "owner")!!
        assertEquals("queued", row.status)
        assertEquals("sq", row.quality)
        assertEquals(999L, row.totalBytes)
        assertEquals("partial", part.readText())
        controller.updateQueuedNetworkPolicy("owner", false)
        assertEquals(updated.generation, work().generation)
    }

    @Test fun reEnablingWifiUpdatesWaitingMobileTasks() = runBlocking {
        controller.enqueue(track, "owner", "hq", false, "Playlist")
        val original = work()
        controller.updateQueuedNetworkPolicy("owner", true)
        assertEquals(original.id, work().id)
        assertEquals(NetworkType.UNMETERED, work().constraints.requiredNetworkType)
        assertEquals("queued_wifi", db.downloads().find(track.id, "owner")!!.status)
        assertEquals("hq", db.downloads().find(track.id, "owner")!!.quality)
    }

    @Test fun policyChangeDoesNotResumePausedOrCompletedOrOtherAccountDownloads() = runBlocking {
        for ((id, status) in listOf("paused-song" to "paused", "complete-song" to "complete", "running-song" to "downloading")) {
            controller.enqueue(track.copy(id = id), "owner", "sq", true, "Playlist")
            db.downloads().progress(id, "owner", status, 10, 20)
        }
        controller.enqueue(track, "other-owner", "sq", true, "Playlist")
        controller.updateQueuedNetworkPolicy("owner", false)

        for ((id, status) in listOf("paused-song" to "paused", "complete-song" to "complete", "running-song" to "downloading")) {
            assertEquals(status, db.downloads().find(id, "owner")!!.status)
            assertEquals(NetworkType.UNMETERED, work(id = id).constraints.requiredNetworkType)
            db.downloads().updateQueuedStatus(id, "owner", "queued")
            assertEquals(status, db.downloads().find(id, "owner")!!.status)
        }
        assertEquals("queued_wifi", db.downloads().find(track.id, "other-owner")!!.status)
        assertEquals(NetworkType.UNMETERED, work("other-owner").constraints.requiredNetworkType)
    }
}
