package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneTest {
    private val certificate = "f".repeat(64)
    private val base = "https://8.138.134.236/".toHttpUrl()

    @Test fun releaseBaseAndApkUrlsAreStrictlyScoped() {
        assertEquals(base, requireControlPlaneBaseUrl(base.toString()))
        assertThrows(IllegalArgumentException::class.java) { requireControlPlaneBaseUrl("https://8.138.134.236:8443/") }
        assertTrue(isTrustedControlDownloadUrl("https://8.138.134.236/download/qmusic-watch/QMusic.apk", base))
        assertFalse(isTrustedControlDownloadUrl("http://8.138.134.236/download/qmusic-watch/QMusic.apk", base))
        assertFalse(isTrustedControlDownloadUrl("https://8.138.134.236:8443/download/qmusic-watch/QMusic.apk", base))
        assertFalse(isTrustedControlDownloadUrl("https://example.com/download/qmusic-watch/QMusic.apk", base))
        assertFalse(isTrustedControlDownloadUrl("https://8.138.134.236/download/qmusic-watch/QMusic.apk?token=x", base))
        assertFalse(isTrustedControlDownloadUrl("https://8.138.134.236/download/other/QMusic.apk", base))
    }

    @Test fun representativeApiEnvelopeDecodesWithForwardCompatibleFields() {
        val value = Json { ignoreUnknownKeys = true }.decodeFromString<ControlApiResult<ControlAnnouncements>>(
            """{"ok":true,"requestId":"req-1","data":{"items":[{"id":"7","title":"维护","content":"说明","enabled":true,"pinned":true,"startsAt":0,"endsAt":0}]}}""",
        )
        assertTrue(value.ok)
        assertEquals("7", value.data?.items?.single()?.id)
        assertTrue(value.data?.items?.single()?.pinned == true)
    }

    @Test fun updateContractRequiresANewerCanonicalApk() {
        val valid = ControlUpdate(
            hasUpdate = true, releaseId = 1, versionName = "0.9.6", versionCode = 36,
            apk = ControlApk(
                "https://8.138.134.236/download/qmusic-watch/QMusic.apk",
                MIN_UPDATE_APK_BYTES, "a".repeat(64), certificate, "com.ronan.qmusicwatch",
            ),
        )
        assertEquals(valid, validateUpdateContract(valid, 35, "com.ronan.qmusicwatch", certificate) { isTrustedControlDownloadUrl(it, base) })
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateContract(valid.copy(versionCode = 35), 35, "com.ronan.qmusicwatch", certificate) { true }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateContract(valid.copy(apk = valid.apk.copy(certificateSha256 = "b".repeat(64))), 35, "com.ronan.qmusicwatch", certificate) { true }
        }
    }

    @Test fun noUpdateMayOmitApkMetadata() {
        val value = ControlUpdate(hasUpdate = false, versionCode = 35, versionName = "0.9.5")
        assertEquals(value, validateUpdateContract(value, 35, "com.ronan.qmusicwatch", certificate) { false })
    }

    @Test fun announcementTargetingAndFeatureDefaultsFailOpen() {
        val now = 10_000L
        val items = listOf(
            ControlAnnouncement("visible", minVersionCode = 35, maxVersionCode = 35),
            ControlAnnouncement("future", startsAt = now + 1),
            ControlAnnouncement("old", endsAt = now - 1),
            ControlAnnouncement("disabled", enabled = false),
        )
        assertEquals(listOf("visible"), visibleAnnouncements(items, 35, now).map { it.id })
        assertTrue(RemoteFeatureConfig().featureEnabled("stream"))
        assertFalse(RemoteFeatureConfig(features = mapOf("stream" to false)).featureEnabled("stream"))
    }

    @Test fun disabledRemoteFlagsExpireAndThenFailOpen() {
        val cache = CachedControlPlane(
            config = RemoteFeatureConfig(features = mapOf("stream" to false), ttlSeconds = 300),
            fetchedAt = 1_000,
        )
        assertTrue(controlCacheIsFresh(cache, 300_999))
        assertFalse(controlCacheIsFresh(cache, 301_000))
        assertTrue((cache.config.takeIf { controlCacheIsFresh(cache, 301_000) } ?: RemoteFeatureConfig()).featureEnabled("stream"))
    }
}
