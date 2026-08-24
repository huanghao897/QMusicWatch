package com.ronan.qmusicwatch.download

import android.content.Context
import androidx.work.*
import com.ronan.qmusicwatch.QMusicApplication
import com.ronan.qmusicwatch.data.*
import com.ronan.qmusicwatch.model.LyricsData
import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.model.QUALITY_LEGACY_UNKNOWN
import com.ronan.qmusicwatch.model.QUALITY_STANDARD
import com.ronan.qmusicwatch.model.normalizeQualityId
import com.ronan.qmusicwatch.network.trustedQMusicImageUrl
import com.ronan.qmusicwatch.network.trustedQMusicMediaUrl
import com.ronan.qmusicwatch.network.withQqMusicMediaHeaders
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
internal fun offlineAudioRelativePath(owner: String, trackId: String): String =
    "offline/${downloadHash(owner)}/${downloadHash("$owner:$trackId")}.audio"
private fun downloadHash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
private val downloadSlots = Semaphore(2)
private val downloadHttp = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        require(
            trustedQMusicMediaUrl(chain.request().url.toString()).isNotBlank() ||
                trustedQMusicImageUrl(chain.request().url.toString()).isNotBlank()
        ) {
            "download host rejected"
        }
        chain.proceed(chain.request())
    }
    .build()
private val downloadJson = Json { ignoreUnknownKeys = true }
private const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
private class StorageReserveException : IllegalStateException("存储空间不足，需保留 256MB")
private class AccountChangedException : IllegalStateException("下载所属账号已变更")
internal data class DownloadContentRange(val start: Long, val end: Long, val total: Long)

internal fun parseDownloadContentRange(value: String?): DownloadContentRange? {
    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return DownloadContentRange(start, end, total).takeIf { start <= end && end < total }
}
internal fun hasDownloadSpace(availableBytes: Long, remainingDownloadBytes: Long): Boolean =
    availableBytes >= STORAGE_RESERVE_BYTES && (remainingDownloadBytes < 0 || availableBytes - remainingDownloadBytes >= STORAGE_RESERVE_BYTES)
internal fun canResumePartialDownload(storedQuality: String?, nextQuality: String): Boolean =
    storedQuality != null && storedQuality != QUALITY_LEGACY_UNKNOWN &&
        normalizeQualityId(storedQuality) == normalizeQualityId(nextQuality)

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = downloadSlots.withPermit { withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val graph = applicationContext as QMusicApplication
        val db = graph.db
        val target = File(applicationContext.filesDir, offlineAudioRelativePath(owner, id))
        val dir = target.parentFile ?: return@withContext Result.failure(workDataOf("reason" to "下载目录无效"))
        dir.mkdirs()
        val part = File(dir, target.name.removeSuffix(".audio") + ".part")
        val requestedQuality = normalizeQualityId(inputData.getString("quality") ?: QUALITY_STANDARD)
        val existing = db.downloads().find(id, owner)
        if (part.exists() && existing == null) part.delete()
        val initial = existing?.copy(
            title = inputData.getString("title").orEmpty(),
            artists = inputData.getString("artists").orEmpty(),
            artworkUrl = inputData.getString("artwork").orEmpty(),
            status = "downloading",
            downloadedBytes = part.length(),
            updatedAt = System.currentTimeMillis(),
            groupName = inputData.getString("group").orEmpty().ifBlank { "单曲缓存" },
        ) ?: DownloadEntity(id, owner, inputData.getString("title").orEmpty(), inputData.getString("artists").orEmpty(), inputData.getString("artwork").orEmpty(), target.absolutePath, "downloading", part.length(), groupName = inputData.getString("group").orEmpty().ifBlank { "单曲缓存" }, quality = requestedQuality)
        db.downloads().upsert(initial)
        if (graph.vault.load()?.accountId != owner) { db.downloads().progress(id, owner, "locked", part.length(), -1); return@withContext Result.failure() }
        fun availableBytes() = if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { applicationContext.getSystemService(android.os.storage.StorageManager::class.java).getAllocatableBytes(android.os.storage.StorageManager.UUID_DEFAULT) }.getOrDefault(dir.usableSpace) else dir.usableSpace
        if (!hasDownloadSpace(availableBytes(), -1)) { db.downloads().progress(id, owner, "failed_storage", part.length(), -1); return@withContext Result.failure(workDataOf("reason" to "存储空间不足，需保留 256MB")) }
        runCatching {
            val track = Track(
                id = id, title = inputData.getString("title").orEmpty(), artists = inputData.getString("artists").orEmpty().split(" / ").filter(String::isNotBlank),
                artworkUrl = inputData.getString("artwork").orEmpty(), qualities = inputData.getString("qualities").orEmpty().split(',').filter(String::isNotBlank).ifEmpty { listOf(QUALITY_STANDARD) },
                numericId = inputData.getLong("numericId", 0), mediaMid = inputData.getString("mediaMid").orEmpty(), songType = inputData.getInt("songType", 0), requiresVip = inputData.getBoolean("requiresVip", false),
            )
            val stream = graph.api.stream(track, requestedQuality)
            val streamUrl = trustedQMusicMediaUrl(stream.url)
            if (streamUrl.isBlank()) error("download QQ Music URL rejected")
            val issuedQuality = normalizeQualityId(stream.quality)
            val current = db.downloads().find(id, owner)
            if (part.exists() && !canResumePartialDownload(current?.quality, issuedQuality)) part.delete()
            current?.copy(
                status = "downloading",
                downloadedBytes = part.length(),
                totalBytes = -1,
                updatedAt = System.currentTimeMillis(),
                quality = issuedQuality,
            )?.let { db.downloads().upsert(it) }
            var complete = false
            var totalBytes = -1L
            while (!complete) {
                if (graph.vault.load()?.accountId != owner) {
                    db.downloads().progress(id, owner, "locked", part.length(), totalBytes)
                    throw AccountChangedException()
                }
                val start = part.length()
                val request = Request.Builder().url(streamUrl).withQqMusicMediaHeaders()
                    .apply { if (start > 0) header("Range", "bytes=$start-") }.build()
                downloadHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("download ${response.code}")
                    val body = response.body ?: error("下载响应为空")
                    val bodyLength = body.contentLength()
                    val range = if (response.code == 206) {
                        parseDownloadContentRange(response.header("Content-Range"))
                            ?: error("下载响应缺少有效 Content-Range")
                    } else null
                    val append: Boolean
                    val expectedTotal: Long
                    val expectedBodyLength: Long?
                    if (range != null) {
                        require(range.start == start) { "下载续传位置不匹配" }
                        append = start > 0
                        expectedTotal = range.total
                        expectedBodyLength = range.end - range.start + 1
                    } else {
                        require(response.code == 200) { "下载响应状态无效" }
                        // A 200 response means the server ignored the range; write
                        // from the beginning so an old partial file is truncated.
                        append = false
                        expectedTotal = bodyLength
                        expectedBodyLength = bodyLength.takeIf { it >= 0 }
                    }
                    if (bodyLength >= 0 && expectedBodyLength != null) {
                        require(bodyLength == expectedBodyLength) { "下载响应长度与范围不一致" }
                    }
                    val spaceNeeded = when {
                        expectedTotal < 0 -> bodyLength
                        append -> (expectedTotal - start).coerceAtLeast(0)
                        else -> expectedTotal
                    }
                    if (!hasDownloadSpace(availableBytes(), spaceNeeded)) throw StorageReserveException()
                    totalBytes = expectedTotal
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var last = part.length()
                            while (true) {
                                if (isStopped) throw kotlinx.coroutines.CancellationException("paused")
                                val read = input.read(buffer); if (read < 0) break
                                output.write(buffer, 0, read)
                                if (part.length() - last > 512 * 1024) {
                                    last = part.length()
                                    if (graph.vault.load()?.accountId != owner) {
                                        db.downloads().progress(id, owner, "locked", part.length(), totalBytes)
                                        throw AccountChangedException()
                                    }
                                    if (!hasDownloadSpace(availableBytes(), 0)) throw StorageReserveException()
                                    setProgress(workDataOf("bytes" to last, "total" to totalBytes))
                                    db.downloads().progress(id, owner, "downloading", last, totalBytes)
                                }
                            }
                            // Force data to disk before a completed file is promoted.
                            output.fd.sync()
                        }
                    }
                    val written = part.length()
                    if (expectedTotal >= 0) {
                        require(written <= expectedTotal) { "下载文件超过声明大小" }
                        complete = written == expectedTotal
                        if (!complete && written == start) error("下载响应未返回数据")
                    } else {
                        complete = true
                        totalBytes = written
                    }
                }
            }
            if (!complete || totalBytes >= 0 && part.length() != totalBytes) error("下载文件不完整 (${part.length()}/$totalBytes)")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) error("cannot finalize download")
            db.downloads().progress(id, owner, "complete", target.length(), target.length())
            runCatching { graph.api.lyrics(id) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
                ?.let { cachedLyricsFile(target.absolutePath).writeText(downloadJson.encodeToString(it)) }
            trustedQMusicImageUrl(track.artworkUrl).takeIf(String::isNotBlank)?.let { artwork ->
                val cover = cachedArtworkFile(target.absolutePath)
                val coverPart = File("${cover.absolutePath}.part")
                runCatching {
                    downloadHttp.newCall(Request.Builder().url(artwork).build()).execute().use { response ->
                        if (!response.isSuccessful) error("cover ${response.code}")
                        response.body?.byteStream()?.use { input -> coverPart.outputStream().use(input::copyTo) } ?: error("empty cover")
                    }
                    if (cover.exists()) cover.delete()
                    if (!coverPart.renameTo(cover)) error("cannot finalize cover")
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    coverPart.delete()
                }
            }
        }.fold(onSuccess = { Result.success() }, onFailure = { error ->
            if (error is kotlinx.coroutines.CancellationException && !isStopped) throw error
            val status = when { isStopped -> "paused"; error is StorageReserveException -> "failed_storage"; error is AccountChangedException -> "locked"; else -> "failed" }
            db.downloads().progress(id, owner, status, part.length(), -1)
            if (!isStopped && error !is StorageReserveException && error !is AccountChangedException && runAttemptCount < 2) Result.retry() else Result.failure(workDataOf("reason" to error.message.orEmpty()))
        })
    } }
}

class DownloadController(private val context: Context, private val db: AppDatabase) {
    val downloads = db.downloads().observeAll()
    suspend fun enqueue(track: Track, owner: String, quality: String, wifiOnly: Boolean, groupName: String) {
        val target = File(context.filesDir, offlineAudioRelativePath(owner, track.id))
        val part = File(target.parentFile, target.name.removeSuffix(".audio") + ".part")
        val existing = db.downloads().find(track.id, owner)
        val requestedQuality = normalizeQualityId(quality)
        val canResume = canResumePartialDownload(existing?.quality, requestedQuality)
        if (part.exists() && !canResume) part.delete()
        db.downloads().upsert(DownloadEntity(
            track.id, owner, track.title, track.artists.joinToString(" / "), track.artworkUrl, target.absolutePath,
            if (wifiOnly) "queued_wifi" else "queued", part.length(), if (canResume) existing?.totalBytes ?: -1 else -1, groupName = groupName, quality = requestedQuality,
        ))
        val data = workDataOf(
            "id" to track.id, "owner" to owner, "quality" to requestedQuality, "title" to track.title, "artists" to track.artists.joinToString(" / "), "artwork" to track.artworkUrl, "group" to groupName,
            "qualities" to track.qualities.joinToString(","), "numericId" to track.numericId, "mediaMid" to track.mediaMid, "songType" to track.songType, "requiresVip" to track.requiresVip,
        )
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        WorkManager.getInstance(context).enqueueUniqueWork("download-${owner}-${track.id}", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(network).build()).build())
    }
    suspend fun pause(trackId: String, owner: String) {
        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork("download-$owner-$trackId").result.get(15, TimeUnit.SECONDS) }
        val item = db.downloads().find(trackId, owner)
        markStopped(item)
    }
    suspend fun pauseAll() {
        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelAllWork().result.get(15, TimeUnit.SECONDS) }
        db.downloads().all().filter { it.status in setOf("downloading", "queued", "queued_wifi") }.forEach { markStopped(it) }
    }
    private suspend fun markStopped(item: DownloadEntity?) {
        item ?: return
        val finalized = File(item.filePath).takeIf(File::exists)
        if (finalized != null) db.downloads().progress(item.trackId, item.ownerAccountId, "complete", finalized.length(), finalized.length())
        else db.downloads().progress(item.trackId, item.ownerAccountId, "paused", item.downloadedBytes, item.totalBytes)
    }
    suspend fun delete(trackId: String, owner: String) { pause(trackId, owner); db.downloads().find(trackId, owner)?.let { File(it.filePath).delete(); File(it.filePath.removeSuffix(".audio") + ".part").delete(); cachedLyricsFile(it.filePath).delete(); cachedArtworkFile(it.filePath).let { cover -> cover.delete(); File("${cover.absolutePath}.part").delete() } }; db.downloads().delete(trackId, owner) }
    suspend fun deleteInvalid(owner: String): Int {
        val invalid = db.downloads().all().filter { it.ownerAccountId == owner && (it.status == "complete" && !File(it.filePath).exists() || it.status.startsWith("failed")) }
        invalid.forEach { delete(it.trackId, owner) }
        return invalid.size
    }
    suspend fun deleteLocked(currentOwner: String?): Int {
        val locked = db.downloads().all().filter { it.ownerAccountId != currentOwner }
        locked.forEach { delete(it.trackId, it.ownerAccountId) }
        return locked.size
    }
    suspend fun deleteGroup(owner: String, groupName: String): Int {
        val items = db.downloads().all().filter { it.ownerAccountId == owner && it.groupName == groupName }
        items.forEach { delete(it.trackId, owner) }
        return items.size
    }
}
