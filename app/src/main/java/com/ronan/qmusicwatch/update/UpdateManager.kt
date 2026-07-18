package com.ronan.qmusicwatch.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ronan.qmusicwatch.BuildConfig
import com.ronan.qmusicwatch.network.ControlPlaneClient
import com.ronan.qmusicwatch.network.ControlUpdate
import com.ronan.qmusicwatch.network.MAX_UPDATE_APK_BYTES
import com.ronan.qmusicwatch.network.normalizedSha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val UPDATE_SPACE_RESERVE_BYTES = 32L * 1024 * 1024
private const val MAX_PARTIAL_METADATA_BYTES = 8L * 1024
private val updateMetadataJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

data class ApkArchiveMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val certificateSha256: Set<String>,
)

@Serializable
internal data class PartialUpdateMetadata(
    val releaseId: Long,
    val versionCode: Int,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val packageName: String,
    val certificateSha256: String,
)

internal data class UpdateArtifacts(
    val part: File,
    val metadata: File,
    val ready: File,
)

internal data class RecoveredUpdate(
    val ready: File? = null,
    val partialBytes: Long = 0,
)

internal sealed interface DownloadResponsePlan {
    data class Write(val append: Boolean, val writtenBefore: Long) : DownloadResponsePlan
    data object RetryFromStart : DownloadResponsePlan
}

private data class ParsedContentRange(val start: Long, val end: Long, val total: Long)

internal fun updateArtifacts(directory: File, release: ControlUpdate): UpdateArtifacts {
    val sha256 = normalizedSha256(release.apk.sha256)
    require(sha256.matches(Regex("[a-f0-9]{64}"))) { "安装包缺少有效 SHA-256" }
    val baseName = "qmusic-watch-r${release.releaseId}-$sha256"
    return UpdateArtifacts(
        part = File(directory, "$baseName.part"),
        metadata = File(directory, "$baseName.part.json"),
        ready = File(directory, "$baseName.apk"),
    )
}

internal fun requiredDownloadSpace(totalBytes: Long, partialBytes: Long): Long {
    require(totalBytes >= 0 && partialBytes in 0..totalBytes)
    return Math.addExact(totalBytes - partialBytes, UPDATE_SPACE_RESERVE_BYTES)
}

internal fun expectedPartialMetadata(release: ControlUpdate) = PartialUpdateMetadata(
    releaseId = release.releaseId,
    versionCode = release.versionCode,
    versionName = release.versionName,
    sizeBytes = release.apk.sizeBytes,
    sha256 = normalizedSha256(release.apk.sha256),
    url = release.apk.url,
    packageName = release.apk.packageName,
    certificateSha256 = normalizedSha256(release.apk.certificateSha256),
)

internal fun partialMetadataMatches(metadata: PartialUpdateMetadata?, release: ControlUpdate): Boolean =
    metadata == expectedPartialMetadata(release)

internal fun planDownloadResponse(
    statusCode: Int,
    requestedStart: Long,
    expectedSize: Long,
    contentRange: String?,
    retriedFromStart: Boolean,
): DownloadResponsePlan {
    require(requestedStart in 0 until expectedSize) { "本地更新缓存大小无效" }
    return when (statusCode) {
        200 -> DownloadResponsePlan.Write(append = false, writtenBefore = 0)
        206 -> {
            val range = parseContentRange(contentRange) ?: throw IllegalArgumentException("服务器返回的续传范围无效")
            require(range.start == requestedStart && range.end == expectedSize - 1 && range.total == expectedSize) {
                "服务器返回的续传范围不匹配"
            }
            DownloadResponsePlan.Write(append = requestedStart > 0, writtenBefore = requestedStart)
        }
        416 -> {
            require(requestedStart > 0 && !retriedFromStart) { "服务器拒绝更新下载范围 HTTP 416" }
            DownloadResponsePlan.RetryFromStart
        }
        else -> throw IllegalStateException("更新下载失败 HTTP $statusCode")
    }
}

private fun parseContentRange(value: String?): ParsedContentRange? {
    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return ParsedContentRange(start, end, total).takeIf { start <= end && end < total }
}

internal fun validateApkMetadata(
    actual: ApkArchiveMetadata,
    release: ControlUpdate,
    expectedPackage: String,
    expectedCertificateSha256: String,
) {
    require(actual.packageName == expectedPackage && actual.packageName == release.apk.packageName) { "下载的 APK 包名不匹配" }
    require(actual.versionCode == release.versionCode.toLong()) { "下载的 APK 版本号不匹配" }
    require(actual.versionName == release.versionName) { "下载的 APK 版本名称不匹配" }
    require(actual.certificateSha256 == setOf(normalizedSha256(expectedCertificateSha256))) { "下载的 APK 签名不一致" }
}

class UpdateManager(
    private val context: Context,
    private val client: ControlPlaneClient,
) {
    internal suspend fun recover(release: ControlUpdate): RecoveredUpdate = withContext(Dispatchers.IO) {
        validateReleaseForDownload(release)
        val directory = updateDirectory()
        val artifacts = updateArtifacts(directory, release)
        if (artifacts.ready.exists()) {
            if (runCatching { verifyDownloadedApk(artifacts.ready, release) }.isSuccess) {
                cleanupOtherArtifacts(directory, setOf(artifacts.ready))
                return@withContext RecoveredUpdate(ready = artifacts.ready)
            }
            artifacts.ready.delete()
        }
        cleanupOtherArtifacts(directory, setOf(artifacts.part, artifacts.metadata, artifacts.ready))
        val partialBytes = validatedPartialBytes(artifacts, release)
        RecoveredUpdate(partialBytes = partialBytes)
    }

    suspend fun downloadAndVerify(
        release: ControlUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    ): File = withContext(Dispatchers.IO) {
        validateReleaseForDownload(release)
        val directory = updateDirectory()
        val artifacts = updateArtifacts(directory, release)

        // A previously verified APK remains usable even when the cache volume is now nearly full.
        if (artifacts.ready.exists()) {
            if (runCatching { verifyDownloadedApk(artifacts.ready, release) }.isSuccess) {
                cleanupOtherArtifacts(directory, setOf(artifacts.ready))
                return@withContext artifacts.ready
            }
            artifacts.ready.delete()
        }
        cleanupOtherArtifacts(directory, setOf(artifacts.part, artifacts.metadata, artifacts.ready))

        var partialBytes = validatedPartialBytes(artifacts, release)
        if (partialBytes == release.apk.sizeBytes) {
            val promoted = runCatching { promoteVerifiedPart(artifacts, release, onVerifying) }.getOrNull()
            if (promoted != null) {
                cleanupOtherArtifacts(directory, setOf(promoted))
                return@withContext promoted
            }
            discardPartial(artifacts)
            partialBytes = 0
        }
        require(directory.usableSpace >= requiredDownloadSpace(release.apk.sizeBytes, partialBytes)) {
            "存储空间不足，无法下载更新"
        }

        var retriedFromStart = false
        while (true) {
            coroutineContext.ensureActive()
            ensurePartialMetadata(artifacts, release)
            val start = artifacts.part.length()
            client.download(client.downloadRequest(release.apk.url, start)).use { response ->
                val plan = try {
                    planDownloadResponse(
                        statusCode = response.code,
                        requestedStart = start,
                        expectedSize = release.apk.sizeBytes,
                        contentRange = response.header("Content-Range"),
                        retriedFromStart = retriedFromStart,
                    )
                } catch (error: Throwable) {
                    if (response.code in setOf(206, 416)) discardPartial(artifacts)
                    throw error
                }
                if (plan == DownloadResponsePlan.RetryFromStart) {
                    discardPartial(artifacts)
                    retriedFromStart = true
                    require(directory.usableSpace >= requiredDownloadSpace(release.apk.sizeBytes, 0)) {
                        "存储空间不足，无法重新下载更新"
                    }
                    return@use
                }
                plan as DownloadResponsePlan.Write
                val body = response.body ?: error("更新下载响应为空")
                val responseBytes = body.contentLength()
                val expectedResponseBytes = release.apk.sizeBytes - plan.writtenBefore
                if (responseBytes >= 0) {
                    if (responseBytes != expectedResponseBytes) {
                        discardPartial(artifacts)
                        throw IllegalArgumentException("服务器返回的安装包大小不一致")
                    }
                }
                FileOutputStream(artifacts.part, plan.append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = plan.writtenBefore
                        onProgress(total, release.apk.sizeBytes)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > release.apk.sizeBytes || total > MAX_UPDATE_APK_BYTES) {
                                discardPartial(artifacts)
                                throw IllegalArgumentException("安装包超过声明大小")
                            }
                            output.write(buffer, 0, read)
                            onProgress(total, release.apk.sizeBytes)
                        }
                        output.fd.sync()
                    }
                }
                require(artifacts.part.length() == release.apk.sizeBytes) { "安装包下载不完整" }
                val ready = try {
                    promoteVerifiedPart(artifacts, release, onVerifying)
                } catch (error: Throwable) {
                    discardPartial(artifacts)
                    throw error
                }
                cleanupOtherArtifacts(directory, setOf(ready))
                return@withContext ready
            }
            if (retriedFromStart && !artifacts.part.exists()) continue
        }
        @Suppress("UNREACHABLE_CODE") error("更新下载失败")
    }

    private fun validateReleaseForDownload(release: ControlUpdate) {
        require(release.hasUpdate) { "没有可下载的新版本" }
        require(release.releaseId > 0) { "更新版本标识无效" }
        require(release.versionCode > BuildConfig.VERSION_CODE) { "更新版本号无效" }
        require(client.trustedDownloadUrl(release.apk.url)) { "安装包下载地址不受信任" }
        require(release.apk.sizeBytes in 1..MAX_UPDATE_APK_BYTES) { "安装包大小异常" }
        require(normalizedSha256(release.apk.sha256).matches(Regex("[a-f0-9]{64}"))) { "安装包缺少有效 SHA-256" }
        require(release.apk.packageName == BuildConfig.APPLICATION_ID) { "安装包包名声明不匹配" }
        require(normalizedSha256(release.apk.certificateSha256) == normalizedSha256(BuildConfig.QMUSIC_RELEASE_CERT_SHA256)) {
            "安装包签名声明不匹配"
        }
    }

    private fun updateDirectory(): File = File(context.cacheDir, "updates").apply { mkdirs() }.also {
        require(it.isDirectory) { "无法创建更新缓存目录" }
    }

    private fun validatedPartialBytes(artifacts: UpdateArtifacts, release: ControlUpdate): Long {
        val metadata = readPartialMetadata(artifacts.metadata)
        val length = artifacts.part.takeIf(File::isFile)?.length() ?: -1
        val valid = partialMetadataMatches(metadata, release) && length in 0..release.apk.sizeBytes
        if (!valid) {
            discardPartial(artifacts)
            return 0
        }
        return length
    }

    private fun ensurePartialMetadata(artifacts: UpdateArtifacts, release: ControlUpdate) {
        if (artifacts.part.exists() && partialMetadataMatches(readPartialMetadata(artifacts.metadata), release)) return
        discardPartial(artifacts)
        writePartialMetadata(artifacts.metadata, expectedPartialMetadata(release))
    }

    private fun promoteVerifiedPart(
        artifacts: UpdateArtifacts,
        release: ControlUpdate,
        onVerifying: () -> Unit,
    ): File {
        onVerifying()
        verifyDownloadedApk(artifacts.part, release)
        artifacts.ready.delete()
        if (!artifacts.part.renameTo(artifacts.ready)) error("无法完成安装包写入")
        artifacts.metadata.delete()
        return artifacts.ready
    }

    private fun readPartialMetadata(file: File): PartialUpdateMetadata? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_PARTIAL_METADATA_BYTES) return@runCatching null
        updateMetadataJson.decodeFromString<PartialUpdateMetadata>(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writePartialMetadata(file: File, metadata: PartialUpdateMetadata) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.delete()
        val bytes = updateMetadataJson.encodeToString(metadata).encodeToByteArray()
        require(bytes.size <= MAX_PARTIAL_METADATA_BYTES) { "更新缓存元数据异常" }
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        file.delete()
        if (!temporary.renameTo(file)) {
            temporary.delete()
            error("无法写入更新缓存元数据")
        }
    }

    private fun discardPartial(artifacts: UpdateArtifacts) {
        artifacts.part.delete()
        artifacts.metadata.delete()
        File(artifacts.metadata.parentFile, "${artifacts.metadata.name}.tmp").delete()
    }

    private fun cleanupOtherArtifacts(directory: File, keep: Set<File>) {
        val keepPaths = keep.mapTo(mutableSetOf()) { it.absolutePath }
        directory.listFiles()?.filter { file ->
            file.name.startsWith("qmusic-watch-") && file.absolutePath !in keepPaths
        }?.forEach(File::delete)
    }

    fun verifyDownloadedApk(file: File, release: ControlUpdate) {
        require(file.isFile && file.length() == release.apk.sizeBytes) { "安装包文件大小不匹配" }
        require(fileSha256(file) == normalizedSha256(release.apk.sha256)) { "安装包 SHA-256 校验失败" }
        val archive = readArchiveMetadata(file) ?: error("系统无法解析下载的 APK")
        validateApkMetadata(archive, release, BuildConfig.APPLICATION_ID, BuildConfig.QMUSIC_RELEASE_CERT_SHA256)
        val installed = readInstalledMetadata() ?: error("系统无法读取当前应用签名")
        require(installed.packageName == BuildConfig.APPLICATION_ID) { "当前应用包名异常" }
        require(installed.certificateSha256 == setOf(normalizedSha256(BuildConfig.QMUSIC_RELEASE_CERT_SHA256))) { "当前安装版本签名不是规范签名" }
    }

    @Suppress("DEPRECATION")
    private fun readArchiveMetadata(file: File): ApkArchiveMetadata? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        return info?.toMetadata()
    }

    @Suppress("DEPRECATION")
    private fun readInstalledMetadata(): ApkArchiveMetadata? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else context.packageManager.getPackageInfo(context.packageName, flags)
        return info.toMetadata()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toMetadata(): ApkArchiveMetadata {
        val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners.orEmpty() else signatures.orEmpty()
        return ApkArchiveMetadata(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
            certificateSha256 = signatures.mapTo(mutableSetOf()) { signature -> sha256(signature.toByteArray()) },
        )
    }
}

object UpdateInstaller {
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    } else Intent(Settings.ACTION_SECURITY_SETTINGS)

    fun installIntent(context: Context, apk: File): Intent {
        require(apk.isFile) { "安装包不存在" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(context.contentResolver, apk.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

internal fun fileSha256(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
