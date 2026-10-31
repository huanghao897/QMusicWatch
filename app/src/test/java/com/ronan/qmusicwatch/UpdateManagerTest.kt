package com.ronan.qmusicwatch

import android.app.Application
import com.ronan.qmusicwatch.network.ControlApk
import com.ronan.qmusicwatch.network.ControlUpdate
import com.ronan.qmusicwatch.network.UpdateUiState
import com.ronan.qmusicwatch.update.ApkArchiveMetadata
import com.ronan.qmusicwatch.update.DownloadResponsePlan
import com.ronan.qmusicwatch.update.PartialUpdateMetadata
import com.ronan.qmusicwatch.update.UpdateManager
import com.ronan.qmusicwatch.update.expectedPartialMetadata
import com.ronan.qmusicwatch.update.fileSha256
import com.ronan.qmusicwatch.update.partialMetadataMatches
import com.ronan.qmusicwatch.update.planDownloadResponse
import com.ronan.qmusicwatch.update.requiredDownloadSpace
import com.ronan.qmusicwatch.update.updateArtifacts
import com.ronan.qmusicwatch.update.validateApkMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class UpdateManagerTest {
    private val certificate = "c".repeat(64)
    private val apkSha256 = "a".repeat(64)
    private val release = ControlUpdate(
        hasUpdate = true, releaseId = 71, versionName = "0.9.6", versionCode = 36,
        apk = ControlApk(
            url = "https://heyboxlite.xyz/download/qmusic-watch/QMusic.apk",
            sizeBytes = 100L * 1024 * 1024,
            sha256 = apkSha256,
            packageName = "com.ronan.qmusicwatch",
            certificateSha256 = certificate,
        ),
    )

    @Test fun metadataMustMatchPackageVersionAndSigner() {
        val valid = ApkArchiveMetadata("com.ronan.qmusicwatch", "0.9.6", 36, setOf(certificate))
        validateApkMetadata(valid, release, "com.ronan.qmusicwatch", certificate)
        assertThrows(IllegalArgumentException::class.java) { validateApkMetadata(valid.copy(versionCode = 37), release, "com.ronan.qmusicwatch", certificate) }
        assertThrows(IllegalArgumentException::class.java) { validateApkMetadata(valid.copy(versionName = "0.9.7"), release, "com.ronan.qmusicwatch", certificate) }
        assertThrows(IllegalArgumentException::class.java) { validateApkMetadata(valid.copy(certificateSha256 = setOf("d".repeat(64))), release, "com.ronan.qmusicwatch", certificate) }
    }

    @Test fun fileHashUsesFullBytes() {
        val file = File.createTempFile("qmusic-update", ".apk")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fileSha256(file))
        } finally { file.delete() }
    }

    @Test fun updateArtifactsAreKeyedByReleaseAndFullHash() {
        val directory = File("updates")
        val first = updateArtifacts(directory, release)
        val replacement = updateArtifacts(directory, release.copy(apk = release.apk.copy(sha256 = "b".repeat(64))))
        val anotherRelease = updateArtifacts(directory, release.copy(releaseId = 72))

        assertTrue(first.part.name.contains("r71-$apkSha256"))
        assertTrue(first.ready.name.contains("r71-$apkSha256"))
        assertFalse(first.part.name == replacement.part.name)
        assertFalse(first.ready.name == anotherRelease.ready.name)
    }

    @Test fun resumeSpaceBudgetUsesOnlyRemainingBytesPlusReserve() {
        val reserve = 32L * 1024 * 1024
        assertEquals(100L * 1024 * 1024 + reserve, requiredDownloadSpace(100L * 1024 * 1024, 0))
        assertEquals(25L * 1024 * 1024 + reserve, requiredDownloadSpace(100L * 1024 * 1024, 75L * 1024 * 1024))
        assertEquals(reserve, requiredDownloadSpace(100L * 1024 * 1024, 100L * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { requiredDownloadSpace(10, 11) }
    }

    @Test fun partialMetadataMustMatchTheExactReleaseArtifact() {
        val metadata = expectedPartialMetadata(release)
        assertTrue(partialMetadataMatches(metadata, release))
        assertFalse(partialMetadataMatches(metadata, release.copy(releaseId = 72)))
        assertFalse(partialMetadataMatches(metadata, release.copy(apk = release.apk.copy(sizeBytes = release.apk.sizeBytes + 1))))
        assertFalse(partialMetadataMatches(metadata, release.copy(apk = release.apk.copy(url = release.apk.url.replace("QMusic", "Other")))))
        assertFalse(partialMetadataMatches(metadata, release.copy(apk = release.apk.copy(sha256 = "b".repeat(64)))))
    }

    @Test fun responsePlansMakeFullResumeAndRangeRecoveryDeterministic() {
        val size = 1_000L
        assertEquals(
            DownloadResponsePlan.Write(append = false, writtenBefore = 0),
            planDownloadResponse(200, requestedStart = 400, expectedSize = size, contentRange = null, retriedFromStart = false),
        )
        assertEquals(
            DownloadResponsePlan.Write(append = true, writtenBefore = 400),
            planDownloadResponse(206, requestedStart = 400, expectedSize = size, contentRange = "bytes 400-999/1000", retriedFromStart = false),
        )
        assertEquals(
            DownloadResponsePlan.Write(append = false, writtenBefore = 0),
            planDownloadResponse(206, requestedStart = 0, expectedSize = size, contentRange = "bytes 0-999/1000", retriedFromStart = false),
        )
        // Truncated 206 responses (CDNs capping Range payloads) must be accepted:
        // the download loop requests the next segment instead of failing forever.
        assertEquals(
            DownloadResponsePlan.Write(append = true, writtenBefore = 400),
            planDownloadResponse(206, requestedStart = 400, expectedSize = size, contentRange = "bytes 400-499/1000", retriedFromStart = false),
        )
        assertEquals(
            DownloadResponsePlan.RetryFromStart,
            planDownloadResponse(416, requestedStart = 400, expectedSize = size, contentRange = "bytes */1000", retriedFromStart = false),
        )
        assertThrows(IllegalArgumentException::class.java) {
            planDownloadResponse(206, requestedStart = 400, expectedSize = size, contentRange = "bytes 0-999/1000", retriedFromStart = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planDownloadResponse(416, requestedStart = 400, expectedSize = size, contentRange = null, retriedFromStart = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planDownloadResponse(416, requestedStart = 0, expectedSize = size, contentRange = null, retriedFromStart = false)
        }
    }

    @Test fun automaticInstallWaitsForTheMatchingVerifiedRelease() {
        val ready = UpdateUiState.Ready(release, "verified.apk")

        assertNull(automaticInstallCandidate(0, ready))
        assertNull(automaticInstallCandidate(release.releaseId + 1, ready))
        assertNull(automaticInstallCandidate(release.releaseId, UpdateUiState.Available(release)))
        assertEquals(ready, automaticInstallCandidate(release.releaseId, ready))
        assertEquals(release, updateStateRelease(UpdateUiState.Downloading(release, 50, 100)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateManagerConcurrencyRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private val directory get() = File(context.cacheDir, "updates")
    private val release = ControlUpdate(
        hasUpdate = true,
        releaseId = 71,
        versionName = "test-next",
        versionCode = BuildConfig.VERSION_CODE + 1,
        apk = ControlApk(
            url = "${BuildConfig.QMUSIC_SERVER_BASE_URL.trimEnd('/')}/download/qmusic-watch/test.apk",
            sizeBytes = 100,
            sha256 = "a".repeat(64),
            packageName = BuildConfig.APPLICATION_ID,
            certificateSha256 = BuildConfig.QMUSIC_RELEASE_CERT_SHA256,
        ),
    )

    @Before fun reset() { directory.deleteRecursively() }
    @After fun cleanUp() { directory.deleteRecursively() }

    private fun response(request: okhttp3.Request, code: Int, body: String, range: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).apply { range?.let { header("Content-Range", it) } }.build()

    @Test fun cancelledHeaderRequestCannotTruncateAnExistingPartial() = runBlocking {
        val headersPending = CountDownLatch(1)
        val releaseHeaders = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            headersPending.countDown()
            check(releaseHeaders.await(5, TimeUnit.SECONDS)) { "headers were not released" }
            response(chain.request(), 200, "x".repeat(100))
        }.build()
        val manager = UpdateManager(context, com.ronan.qmusicwatch.network.ControlPlaneClient(http = http))
        val job = launch(Dispatchers.Default) { manager.downloadAndVerify(release, { _, _ -> }, {}) }
        try {
            assertTrue(headersPending.await(5, TimeUnit.SECONDS))
            val part = updateArtifacts(directory, release).part
            // The original request was made at offset zero. A late response
            // would open this with append=false unless cancellation is checked.
            part.writeText("preserve-this-partial")
            job.cancel()
            releaseHeaders.countDown()
            withTimeout(5_000) { job.join() }
            assertEquals("preserve-this-partial", part.readText())
        } finally {
            releaseHeaders.countDown()
            withTimeout(5_000) { job.cancelAndJoin() }
        }
    }

    @Test fun sameReleaseDownloadsCannotOpenTwoWriters() = assertSecondDownloadWaits(71)

    @Test fun differentReleaseDownloadsCannotDeleteAnActivePartial() = assertSecondDownloadWaits(72)

    @Test fun cancellingRecoveredFullPartialVerificationPreservesTheDownload() = runBlocking {
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            error("Cancellation must not start a replacement download")
        }.build()
        val manager = UpdateManager(context, com.ronan.qmusicwatch.network.ControlPlaneClient(http = http))
        val artifacts = updateArtifacts(directory.apply { mkdirs() }, release)
        val payload = "x".repeat(release.apk.sizeBytes.toInt())
        artifacts.part.writeText(payload)
        val metadata = Json.encodeToString(expectedPartialMetadata(release))
        artifacts.metadata.writeText(metadata)
        val cancelled = CancellationException("Synthetic verification cancellation")

        val error = runCatching { manager.downloadAndVerify(release, { _, _ -> }, { throw cancelled }) }.exceptionOrNull()

        // Coroutine stack-trace recovery may copy the exception across IO.
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message, error?.message)
        assertEquals(0, requests.get())
        assertEquals(payload, artifacts.part.readText())
        assertEquals(metadata, artifacts.metadata.readText())
        assertFalse(artifacts.ready.exists())
    }

    @Test fun cancellingNewFullPartialVerificationPreservesTheDownload() = runBlocking {
        val payload = "x".repeat(release.apk.sizeBytes.toInt())
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            response(chain.request(), 200, payload)
        }.build()
        val manager = UpdateManager(context, com.ronan.qmusicwatch.network.ControlPlaneClient(http = http))
        val cancelled = CancellationException("Synthetic verification cancellation")

        val error = runCatching { manager.downloadAndVerify(release, { _, _ -> }, { throw cancelled }) }.exceptionOrNull()

        val artifacts = updateArtifacts(directory, release)
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message, error?.message)
        assertEquals(1, requests.get())
        assertEquals(payload, artifacts.part.readText())
        assertEquals(expectedPartialMetadata(release), Json.decodeFromString<PartialUpdateMetadata>(artifacts.metadata.readText()))
        assertFalse(artifacts.ready.exists())
    }

    private fun assertSecondDownloadWaits(secondReleaseId: Long) = runBlocking {
        val firstProgress = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        val prefix = "first-partial"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) {
                response(chain.request(), 206, prefix, "bytes 0-${prefix.length - 1}/100")
            } else response(chain.request(), 503, "")
        }.build()
        // Unconfined makes the second UNDISPATCHED call enter the manager
        // synchronously until it suspends on the held mutex. No sleep or
        // scheduler timing assumption is needed to assert that it is waiting.
        val manager = UpdateManager(context, com.ronan.qmusicwatch.network.ControlPlaneClient(http = http), Dispatchers.Unconfined)
        val first = launch(Dispatchers.Default) {
            manager.downloadAndVerify(release, { bytes, _ ->
                if (bytes > 0) {
                    firstProgress.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first writer was not released" }
                }
            }, {})
        }
        var second: kotlinx.coroutines.Deferred<Throwable?>? = null
        try {
            assertTrue(firstProgress.await(5, TimeUnit.SECONDS))
            val part = updateArtifacts(directory, release).part
            assertEquals(prefix, part.readText())
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { manager.downloadAndVerify(release.copy(releaseId = secondReleaseId), { _, _ -> }, {}) }.exceptionOrNull()
            }
            second = next
            assertFalse(next.isCompleted)
            assertEquals(1, requests.get())
            assertEquals(prefix, part.readText())

            first.cancel()
            releaseFirst.countDown()
            withTimeout(5_000) { first.join() }
            val error = withTimeout(5_000) { next.await() }
            assertTrue(error?.message.orEmpty().contains("503"))
            assertEquals(2, requests.get())
            if (secondReleaseId == release.releaseId) assertEquals(prefix, part.readText())
            else assertFalse(part.exists())
        } finally {
            releaseFirst.countDown()
            withTimeout(5_000) { first.cancelAndJoin(); second?.cancelAndJoin() }
        }
    }

    @Test fun recoveryCannotCleanAnotherReleasesActivePartial() = runBlocking {
        val firstProgress = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val prefix = "first-partial"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 206, prefix, "bytes 0-${prefix.length - 1}/100")
        }.build()
        val manager = UpdateManager(context, com.ronan.qmusicwatch.network.ControlPlaneClient(http = http), Dispatchers.Unconfined)
        val first = launch(Dispatchers.Default) {
            manager.downloadAndVerify(release, { bytes, _ ->
                if (bytes > 0) {
                    firstProgress.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first writer was not released" }
                }
            }, {})
        }
        var recovery: kotlinx.coroutines.Deferred<com.ronan.qmusicwatch.update.RecoveredUpdate>? = null
        try {
            assertTrue(firstProgress.await(5, TimeUnit.SECONDS))
            val part = updateArtifacts(directory, release).part
            val next = async(start = CoroutineStart.UNDISPATCHED) { manager.recover(release.copy(releaseId = 72)) }
            recovery = next
            assertFalse(next.isCompleted)
            assertEquals(prefix, part.readText())
            first.cancel()
            releaseFirst.countDown()
            withTimeout(5_000) { first.join(); next.await() }
            assertFalse(part.exists())
        } finally {
            releaseFirst.countDown()
            withTimeout(5_000) { first.cancelAndJoin(); recovery?.cancelAndJoin() }
        }
    }
}
