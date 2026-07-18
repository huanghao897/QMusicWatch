package com.ronan.qmusicwatch.network

import kotlinx.serialization.Serializable

@Serializable
data class ControlApiResult<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ControlApiError? = null,
)

@Serializable
data class ControlApiError(val code: String = "UNKNOWN", val message: String = "")

@Serializable
data class ControlApk(
    val url: String = "",
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val certificateSha256: String = "",
    val packageName: String = "",
)

@Serializable
data class ControlUpdate(
    val hasUpdate: Boolean = false,
    val releaseId: Long = 0,
    val versionName: String = "",
    val versionCode: Int = 0,
    val title: String = "",
    val changelog: String = "",
    val forceUpdate: Boolean = false,
    val channel: String = "stable",
    val publishedAt: Long = 0,
    val apk: ControlApk = ControlApk(),
)

@Serializable
data class ControlAnnouncement(
    val id: String,
    val title: String = "公告",
    val content: String = "",
    val severity: String = "info",
    val enabled: Boolean = true,
    val pinned: Boolean = false,
    val startsAt: Long = 0,
    val endsAt: Long = 0,
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val onceOnly: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable data class ControlAnnouncements(val items: List<ControlAnnouncement> = emptyList())

@Serializable
data class RemoteFeatureConfig(
    val features: Map<String, Boolean> = emptyMap(),
    val messages: Map<String, String> = emptyMap(),
    val ttlSeconds: Long = 21_600,
    val revision: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class CachedControlPlane(
    val config: RemoteFeatureConfig = RemoteFeatureConfig(),
    val announcements: List<ControlAnnouncement> = emptyList(),
    val fetchedAt: Long = 0,
)

@Serializable
data class DiagnosticUpload(
    val version: String,
    val versionCode: Int,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val report: String,
)

@Serializable data class DiagnosticReceipt(val requestId: String = "", val accepted: Boolean = true)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object NoUpdate : UpdateUiState
    data class Available(val release: ControlUpdate) : UpdateUiState
    data class Downloading(val release: ControlUpdate, val downloadedBytes: Long, val totalBytes: Long) : UpdateUiState
    data class Verifying(val release: ControlUpdate) : UpdateUiState
    data class Ready(val release: ControlUpdate, val filePath: String) : UpdateUiState
    data class Error(val message: String, val release: ControlUpdate? = null) : UpdateUiState
}

sealed interface DiagnosticUploadState {
    data object Idle : DiagnosticUploadState
    data object Uploading : DiagnosticUploadState
    data class Success(val requestId: String) : DiagnosticUploadState
    data class Error(val message: String) : DiagnosticUploadState
}

internal const val MIN_UPDATE_APK_BYTES = 128L * 1024
internal const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024

internal fun normalizedSha256(value: String): String = value.trim().lowercase()

internal fun validateUpdateContract(
    release: ControlUpdate,
    currentVersionCode: Int,
    expectedPackage: String,
    expectedCertificateSha256: String,
    trustedDownloadUrl: (String) -> Boolean,
): ControlUpdate {
    if (!release.hasUpdate) return release
    require(release.releaseId > 0) { "服务器返回的更新标识无效" }
    require(release.versionCode > currentVersionCode) { "服务器返回的版本号无效" }
    require(release.versionName.isNotBlank() && release.versionName.length <= 40) { "服务器返回的版本名称无效" }
    require(release.apk.sizeBytes in MIN_UPDATE_APK_BYTES..MAX_UPDATE_APK_BYTES) { "安装包大小异常" }
    require(normalizedSha256(release.apk.sha256).matches(Regex("[a-f0-9]{64}"))) { "安装包缺少有效 SHA-256" }
    require(release.apk.packageName == expectedPackage) { "安装包包名不匹配" }
    require(normalizedSha256(release.apk.certificateSha256) == normalizedSha256(expectedCertificateSha256)) { "安装包签名声明不匹配" }
    require(trustedDownloadUrl(release.apk.url)) { "安装包下载地址不受信任" }
    return release
}

internal fun visibleAnnouncements(
    items: List<ControlAnnouncement>,
    versionCode: Int,
    now: Long,
): List<ControlAnnouncement> = items.filter { item ->
    item.enabled && item.id.isNotBlank() && item.id.length <= 100 && item.severity in setOf("info", "warning", "critical") &&
        (item.startsAt <= 0 || item.startsAt <= now) &&
        (item.endsAt <= 0 || item.endsAt >= now) &&
        (item.minVersionCode <= 0 || item.minVersionCode <= versionCode) &&
        (item.maxVersionCode <= 0 || item.maxVersionCode >= versionCode)
}.distinctBy(ControlAnnouncement::id).take(40)

internal fun RemoteFeatureConfig.featureEnabled(name: String): Boolean = features[name] != false

internal fun controlCacheIsFresh(cache: CachedControlPlane, now: Long): Boolean {
    val ttlMs = cache.config.ttlSeconds.coerceIn(300, 86_400) * 1000
    return cache.fetchedAt > 0 && now >= cache.fetchedAt && now - cache.fetchedAt < ttlMs
}
