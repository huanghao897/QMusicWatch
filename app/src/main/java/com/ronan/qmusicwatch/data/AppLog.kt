package com.ronan.qmusicwatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private lateinit var file: File
    @Synchronized fun init(context: Context) {
        file = File(context.filesDir, "logs/qmusic-watch.log").apply { parentFile?.mkdirs() }
        write("APP", "start version=${com.ronan.qmusicwatch.BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
    }
    @Synchronized fun write(tag: String, message: String) {
        if (!::file.isInitialized) return
        if (file.length() > 256 * 1024) file.writeText("")
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        file.appendText("$time $tag ${message.replace('\n', ' ').take(1200)}\n")
    }
    @Synchronized fun clear() { if (::file.isInitialized) file.writeText("") }
    fun shareIntent(context: Context): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun copyTo(context: Context, uri: Uri) { context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } }
}
