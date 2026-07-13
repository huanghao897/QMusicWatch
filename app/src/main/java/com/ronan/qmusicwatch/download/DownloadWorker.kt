package com.ronan.qmusicwatch.download

import android.content.Context
import androidx.work.*
import com.ronan.qmusicwatch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val db = AppDatabase.create(applicationContext)
        val ownerDir = hash(owner)
        val fileName = hash("$owner:$id")
        val dir = File(applicationContext.filesDir, "offline/$ownerDir").apply { mkdirs() }
        val part = File(dir, "$fileName.part")
        val target = File(dir, "$fileName.audio")
        db.downloads().upsert(DownloadEntity(id, owner, inputData.getString("title").orEmpty(), inputData.getString("artists").orEmpty(), inputData.getString("artwork").orEmpty(), target.absolutePath, "downloading", part.length(), groupName = inputData.getString("group").orEmpty().ifBlank { "单曲缓存" }))
        val available = if (android.os.Build.VERSION.SDK_INT >= 26) applicationContext.getSystemService(android.os.storage.StorageManager::class.java).getAllocatableBytes(android.os.storage.StorageManager.UUID_DEFAULT) else dir.usableSpace
        if (available < 256L * 1024 * 1024) { db.downloads().progress(id, owner, "failed_storage", part.length(), -1); return@withContext Result.failure(workDataOf("reason" to "存储空间不足，需保留 256MB")) }
        val request = Request.Builder().url(url).apply { if (part.length() > 0) header("Range", "bytes=${part.length()}-") }.build()
        runCatching {
            OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("download ${response.code}")
                val append = response.code == 206 && part.length() > 0
                val start = if (append) part.length() else 0
                val total = response.body?.contentLength()?.let { if (it < 0) -1 else start + it } ?: -1
                response.body!!.byteStream().use { input ->
                    java.io.FileOutputStream(part, append).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var last = 0L
                            while (true) {
                                if (isStopped) throw kotlinx.coroutines.CancellationException("paused")
                                val read = input.read(buffer); if (read < 0) break
                                output.write(buffer, 0, read)
                                if (part.length() - last > 512 * 1024) { last = part.length(); setProgress(workDataOf("bytes" to last, "total" to total)); db.downloads().progress(id, owner, "downloading", last, total) }
                            }
                    }
                }
                if (!part.renameTo(target)) error("cannot finalize download")
                db.downloads().progress(id, owner, "complete", target.length(), target.length())
            }
        }.fold(onSuccess = { Result.success() }, onFailure = { db.downloads().progress(id, owner, if (isStopped) "paused" else "failed", part.length(), -1); if (!isStopped && runAttemptCount < 2) Result.retry() else Result.failure() })
    }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

class DownloadController(private val context: Context, private val db: AppDatabase) {
    val downloads = db.downloads().observeAll()
    fun enqueue(track: com.ronan.qmusicwatch.model.Track, owner: String, url: String, wifiOnly: Boolean, groupName: String) {
        val data = workDataOf("id" to track.id, "owner" to owner, "url" to url, "title" to track.title, "artists" to track.artists.joinToString(" / "), "artwork" to track.artworkUrl, "group" to groupName)
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        WorkManager.getInstance(context).enqueueUniqueWork("download-${owner}-${track.id}", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(network).build()).build())
    }
    suspend fun pause(trackId: String, owner: String) { WorkManager.getInstance(context).cancelUniqueWork("download-$owner-$trackId"); val item = db.downloads().find(trackId, owner); db.downloads().progress(trackId, owner, "paused", item?.downloadedBytes ?: 0, item?.totalBytes ?: -1) }
    suspend fun delete(trackId: String, owner: String) { pause(trackId, owner); db.downloads().find(trackId, owner)?.let { File(it.filePath).delete(); File(it.filePath.removeSuffix(".audio") + ".part").delete() }; db.downloads().delete(trackId, owner) }
    suspend fun deleteInvalid(owner: String): Int {
        val invalid = db.downloads().all().filter { it.ownerAccountId == owner && (it.status == "complete" && !File(it.filePath).exists() || it.status.startsWith("failed")) }
        invalid.forEach { delete(it.trackId, owner) }
        return invalid.size
    }
}
