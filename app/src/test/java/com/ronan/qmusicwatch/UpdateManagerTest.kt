package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.ControlApk
import com.ronan.qmusicwatch.network.ControlUpdate
import com.ronan.qmusicwatch.update.ApkArchiveMetadata
import com.ronan.qmusicwatch.update.DownloadResponsePlan
import com.ronan.qmusicwatch.update.expectedPartialMetadata
import com.ronan.qmusicwatch.update.fileSha256
import com.ronan.qmusicwatch.update.partialMetadataMatches
import com.ronan.qmusicwatch.update.planDownloadResponse
import com.ronan.qmusicwatch.update.requiredDownloadSpace
import com.ronan.qmusicwatch.update.updateArtifacts
import com.ronan.qmusicwatch.update.validateApkMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManagerTest {
    private val certificate = "c".repeat(64)
    private val apkSha256 = "a".repeat(64)
    private val release = ControlUpdate(
        hasUpdate = true, releaseId = 71, versionName = "0.9.6", versionCode = 36,
        apk = ControlApk(
            url = "https://8.138.134.236:8443/download/qmusic-watch/QMusic.apk",
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
}
