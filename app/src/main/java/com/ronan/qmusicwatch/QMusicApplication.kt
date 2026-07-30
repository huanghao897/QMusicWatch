package com.ronan.qmusicwatch

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.ronan.qmusicwatch.data.AppDatabase
import com.ronan.qmusicwatch.data.SessionVault
import com.ronan.qmusicwatch.data.SettingsStore
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.download.DownloadController
import com.ronan.qmusicwatch.network.ApiClient
import com.ronan.qmusicwatch.network.ControlPlaneClient
import com.ronan.qmusicwatch.network.QMUSIC_SERVER_HOST
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import com.ronan.qmusicwatch.playback.PlaybackConnection
import com.ronan.qmusicwatch.update.UpdateManager
import okhttp3.OkHttpClient
import java.io.File

private const val IMAGE_CACHE_DIRECTORY = "qmusic_image_cache"
internal const val IMAGE_CACHE_MAX_SIZE_BYTES = 64L * 1024L * 1024L

internal fun isPersistentQMusicArtworkUrl(value: String): Boolean =
    trustedQMusicMediaUrl(value).contains("/api/qmusic-watch/gateway/artwork/album/")

private fun qMusicImageHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val request = chain.request()
        require(trustedQMusicMediaUrl(request.url.toString()).isNotBlank()) {
            "image host rejected"
        }
        val response = chain.proceed(request)
        if (!isPersistentQMusicArtworkUrl(request.url.toString())) {
            response
        } else {
            // Stable album-art URLs identify immutable images. Override the
            // gateway's API no-store header for artwork only, not avatars.
            response.newBuilder()
                .removeHeader("Pragma")
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .build()
        }
    }
    .build()

internal fun persistentQMusicImageDiskCache(cacheDirectory: File): DiskCache =
    DiskCache.Builder()
        .directory(cacheDirectory)
        .maxSizeBytes(IMAGE_CACHE_MAX_SIZE_BYTES)
        .build()

internal fun persistentQMusicImageLoader(
    context: Context,
    okHttpClient: OkHttpClient = qMusicImageHttpClient(),
    cacheDirectory: File = File(context.filesDir, IMAGE_CACHE_DIRECTORY),
): ImageLoader = ImageLoader.Builder(context.applicationContext)
    .okHttpClient(okHttpClient)
    .diskCache { persistentQMusicImageDiskCache(cacheDirectory) }
    .build()

class QMusicApplication : Application(), ImageLoaderFactory {
    companion object { val processStartedAt: Long = android.os.SystemClock.elapsedRealtime() }
    lateinit var db: AppDatabase
    lateinit var vault: SessionVault
    lateinit var api: ApiClient
    lateinit var controlPlane: ControlPlaneClient
    lateinit var updates: UpdateManager
    lateinit var downloads: DownloadController
    lateinit var playback: PlaybackConnection
    lateinit var settings: SettingsStore
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        db = AppDatabase.create(this)
        vault = SessionVault(this)
        api = ApiClient(
            this,
            cookie = { vault.load()?.upstreamCookie },
            updateCookie = { refreshed ->
                vault.load()?.let { session ->
                    vault.save(session.copy(upstreamCookie = refreshed, gatewayHost = QMUSIC_SERVER_HOST))
                }
            },
        )
        controlPlane = ControlPlaneClient()
        updates = UpdateManager(this, controlPlane)
        downloads = DownloadController(this, db)
        settings = SettingsStore(this)
        playback = PlaybackConnection(this)
    }

    override fun newImageLoader(): ImageLoader = persistentQMusicImageLoader(this)
}
