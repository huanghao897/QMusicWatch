package com.ronan.qmusicwatch

import coil.annotation.ExperimentalCoilApi
import com.ronan.qmusicwatch.network.qmusicAlbumArtworkUrl
import com.ronan.qmusicwatch.network.qmusicAvatarUrl
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoilApi::class)
class ImageCacheTest {
    @Test fun onlyStableAlbumArtworkOverridesGatewayCacheHeaders() {
        assertTrue(isPersistentQMusicArtworkUrl(qmusicAlbumArtworkUrl("albumMid123")))
        assertFalse(isPersistentQMusicArtworkUrl(qmusicAvatarUrl("12345")))
        assertFalse(
            isPersistentQMusicArtworkUrl(
                "https://heyboxlite.xyz/api/qmusic-watch/gateway/media/${"a".repeat(32)}/cover.jpg",
            ),
        )
    }

    @Test fun artworkDiskCacheSurvivesCacheRecreation() {
        val cacheDirectory = Files.createTempDirectory("qmusic-image-cache").toFile()
        try {
            val firstCache = persistentQMusicImageDiskCache(cacheDirectory)
            val editor = requireNotNull(firstCache.openEditor("album-mid-123"))
            FileSystem.SYSTEM.write(editor.data) { writeUtf8("cached-artwork") }
            editor.commit()

            val recreatedCache = persistentQMusicImageDiskCache(cacheDirectory)
            requireNotNull(recreatedCache.openSnapshot("album-mid-123")).use { snapshot ->
                val cached = FileSystem.SYSTEM.read(snapshot.data) { readUtf8() }
                assertEquals("cached-artwork", cached)
            }
            assertEquals(IMAGE_CACHE_MAX_SIZE_BYTES, recreatedCache.maxSize)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }
}
