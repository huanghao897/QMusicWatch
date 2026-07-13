package com.ronan.qmusicwatch

import com.ronan.qmusicwatch.network.isVersionNewer
import com.ronan.qmusicwatch.network.parseGitHubRelease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    @Test fun semanticVersionsCompareNumerically() {
        assertTrue(isVersionNewer("v0.10.0", "0.9.9"))
        assertEquals(false, isVersionNewer("v0.8.6", "0.8.6"))
    }

    @Test fun releaseUsesOnlyRepositoryApkAndDigest() {
        val root = Json.parseToJsonElement("""{"tag_name":"v0.9.0","name":"更新","html_url":"https://github.com/huanghao897/QMusicWatch/releases/tag/v0.9.0","assets":[{"name":"QMusicWatch.apk","browser_download_url":"https://github.com/huanghao897/QMusicWatch/releases/download/v0.9.0/QMusicWatch.apk","digest":"sha256:${"a".repeat(64)}"}]}""").jsonObject
        val release = parseGitHubRelease(root, "0.8.6")
        assertTrue(release.newer)
        assertEquals("a".repeat(64), release.sha256)
    }
}
