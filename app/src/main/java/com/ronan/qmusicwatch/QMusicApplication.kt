package com.ronan.qmusicwatch

import android.app.Application
import com.ronan.qmusicwatch.data.AppDatabase
import com.ronan.qmusicwatch.data.SessionVault
import com.ronan.qmusicwatch.data.SettingsStore
import com.ronan.qmusicwatch.data.AppLog
import com.ronan.qmusicwatch.download.DownloadController
import com.ronan.qmusicwatch.network.ApiClient
import com.ronan.qmusicwatch.playback.PlaybackConnection

class QMusicApplication : Application() {
    lateinit var db: AppDatabase
    lateinit var vault: SessionVault
    lateinit var api: ApiClient
    lateinit var downloads: DownloadController
    lateinit var playback: PlaybackConnection
    lateinit var settings: SettingsStore
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        db = AppDatabase.create(this)
        vault = SessionVault(this)
        api = ApiClient(this) { vault.load()?.upstreamCookie }
        downloads = DownloadController(this, db)
        playback = PlaybackConnection(this)
        settings = SettingsStore(this)
    }
}
