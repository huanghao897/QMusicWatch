package com.ronan.qmusicwatch.download

import android.content.Context
import androidx.work.*
import com.ronan.qmusicwatch.QMusicApplication
import com.ronan.qmusicwatch.data.*
import com.ronan.qmusicwatch.model.LyricsData
import com.ronan.qmusicwatch.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

fun cachedLyricsFile(audioPath: String) = File(audioPath.removeSuffix(".audio") + ".lyrics.json")
fun cachedArtworkFile(audioPath: String) = File(audioPath.removeSuffix(".audio") + ".cover")
private val downloadSlots = Semaphore(2)
private val downloadHttp = OkHttpClient()
private val downloadJson = Json { ignoreUnknownKeys = true }

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = downloadSlots.withPermit { withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val graph = applicationContext as QMusicApplication
        val db = graph.db
        val ownerDir = hash(owner)
        val fileName = hash("$owner:$id")
        val dir = File(applicationContext.filesDir, "offline/$ownerDir").apply { mkdirs() }
        val part = File(dir, "$fileName.part")
        val target = File(dir, "$fileName.audio")
        db.downloads().upsert(DownloadEntity(id, owner, inputData.getString("title").orEmpty(), inputData.getString("artists").orEmpty(), inputData.getString("artwork").orEmpty(), target.absolutePath, "downloading", part.length(), groupName = inputData.getString("group").orEmpty().ifBlank { "单曲缓存" }))
        if (graph.vault.load()?.accountId != owner) { db.downloads().progress(id, owner, "locked", part.length(), -1); return@withContext Result.failure() }
        val available = if (android.os.Build.VERSION.SDK_INT >= 26) applicationContext.getSystemService(android.os.storage.StorageManager::class.java).getAllocatableBytes(android.os.storage.StorageManager.UUID_DEFAULT) else dir.usableSpace
        if (available < 256L * 1024 * 1024) { db.downloads().progress(id, owner, "failed_storage", part.length(), -1); return@withContext Result.failure(workDataOf("reason" to "存储空间不足，需保留 256MB")) }
        runCatching {
            val track = Track(
                id = id, title = inputData.getString("title").orEmpty(), artists = inputData.getString("artists").orEmpty().split(" / ").filter(String::isNotBlank),
                artworkUrl = inputData.getString("artwork").orEmpty(), qualities = inputData.getString("qualities").orEmpty().split(',').filter(String::isNotBlank).ifEmpty { listOf("128") },
                numericId = inputData.getLong("numericId", 0), mediaMid = inputData.getString("mediaMid").orEmpty(), songType = inputData.getInt("songType", 0), requiresVip = inputData.getBoolean("requiresVip", false),
            )
            val stream = graph.api.stream(track, inputData.getString("quality") ?: "128")
            val request = Request.Builder().url(stream.url).header("Referer", "https://y.qq.com/").header("Origin", "https://y.qq.com/").apply { if (part.length() > 0) header("Range", "bytes=${part.length()}-") }.build()
            downloadHttp.newCall(request).execute().use { response ->
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
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) error("cannot finalize download")
                db.downloads().progress(id, owner, "complete", target.length(), target.length())
            }
            runCatching { graph.api.lyrics(id) }.getOrNull()?.let { cachedLyricsFile(target.absolutePath).writeText(downloadJson.encodeToString(it)) }
            track.artworkUrl.takeIf { it.startsWith("https://") }?.let { artwork ->
                val cover = cachedArtworkFile(target.absolutePath)
                val coverPart = File("${cover.absolutePath}.part")
                runCatching {
                    downloadHttp.newCall(Request.Builder().url(artwork).build()).execute().use { response ->
                        if (!response.isSuccessful) error("cover ${response.code}")
                        response.body?.byteStream()?.use { input -> coverPart.outputStream().use(input::copyTo) } ?: error("empty cover")
                    }
                    if (cover.exists()) cover.delete()
                    if (!coverPart.renameTo(cover)) error("cannot finalize cover")
                }.onFailure { coverPart.delete() }
            }
        }.fold(onSuccess = { Result.success() }, onFailure = { db.downloads().progress(id, owner, if (isStopped) "paused" else "failed", part.length(), -1); if (!isStopped && runAttemptCount < 2) Result.retry() else Result.failure() })
    } }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

class DownloadController(private val context: Context, private val db: AppDatabase) {
    val downloads = db.downloads().observeAll()
    fun enqueue(track: Track, owner: String, quality: String, wifiOnly: Boolean, groupName: String) {
        val data = workDataOf(
            "id" to track.id, "owner" to owner, "quality" to quality, "title" to track.title, "artists" to track.artists.joinToString(" / "), "artwork" to track.artworkUrl, "group" to groupName,
            "qualities" to track.qualities.joinToString(","), "numericId" to track.numericId, "mediaMid" to track.mediaMid, "songType" to track.songType, "requiresVip" to track.requiresVip,
        )
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        WorkManager.getInstance(context).enqueueUniqueWork("download-${owner}-${track.id}", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(network).build()).build())
    }
    suspend fun pause(trackId: String, owner: String) {
        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork("download-$owner-$trackId").result.get(15, TimeUnit.SECONDS) }
        val item = db.downloads().find(trackId, owner)
        val finalized = item?.filePath?.let(::File)?.takeIf(File::exists)
        if (finalized != null) db.downloads().progress(trackId, owner, "complete", finalized.length(), finalized.length())
        else db.downloads().progress(trackId, owner, "paused", item?.downloadedBytes ?: 0, item?.totalBytes ?: -1)
    }
    suspend fun delete(trackId: String, owner: String) { pause(trackId, owner); db.downloads().find(trackId, owner)?.let { File(it.filePath).delete(); File(it.filePath.removeSuffix(".audio") + ".part").delete(); cachedLyricsFile(it.filePath).delete(); cachedArtworkFile(it.filePath).let { cover -> cover.delete(); File("${cover.absolutePath}.part").delete() } }; db.downloads().delete(trackId, owner) }
    suspend fun deleteInvalid(owner: String): Int {
        val invalid = db.downloads().all().filter { it.ownerAccountId == owner && (it.status == "complete" && !File(it.filePath).exists() || it.status.startsWith("failed")) }
        invalid.forEach { delete(it.trackId, owner) }
        return invalid.size
    }
}
