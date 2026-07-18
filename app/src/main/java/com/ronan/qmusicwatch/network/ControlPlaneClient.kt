package com.ronan.qmusicwatch.network

import com.ronan.qmusicwatch.BuildConfig
import com.ronan.qmusicwatch.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val MAX_JSON_RESPONSE_BYTES = 256 * 1024
internal const val MAX_DIAGNOSTIC_REQUEST_BYTES = 64 * 1024

internal fun requireControlPlaneBaseUrl(value: String, allowCleartext: Boolean = false): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("控制面地址无效")
    require(url.scheme == "https" || allowCleartext && url.scheme == "http") { "控制面必须使用 HTTPS" }
    require(url.host == "8.138.134.236" && url.port == 8443) { "控制面主机或端口不匹配" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "控制面地址包含不允许的字段" }
    require(url.encodedPath == "/") { "控制面地址不能包含路径" }
    return url
}
internal fun isTrustedControlDownloadUrl(value: String, baseUrl: HttpUrl): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host == baseUrl.host && url.port == baseUrl.port &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.encodedPath.startsWith("/download/qmusic-watch/") &&
        !url.encodedPath.contains("..") && url.pathSegments.lastOrNull()?.endsWith(".apk", true) == true
}

class ControlPlaneClient(
    baseUrlValue: String = BuildConfig.QMUSIC_SERVER_BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val baseUrl = requireControlPlaneBaseUrl(baseUrlValue, BuildConfig.DEBUG)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun trustedDownloadUrl(value: String): Boolean = isTrustedControlDownloadUrl(value, baseUrl)

    suspend fun latestRelease(): ControlUpdate {
        val url = endpoint("api/qmusic-watch/update").newBuilder()
            .addQueryParameter("versionCode", BuildConfig.VERSION_CODE.toString())
            .addQueryParameter("versionName", BuildConfig.VERSION_NAME)
            .addQueryParameter("channel", "stable")
            .build()
        val release = get<ControlUpdate>(url)
        return validateUpdateContract(
            release,
            BuildConfig.VERSION_CODE,
            BuildConfig.APPLICATION_ID,
            BuildConfig.QMUSIC_RELEASE_CERT_SHA256,
            ::trustedDownloadUrl,
        )
    }

    suspend fun announcements(): List<ControlAnnouncement> {
        val url = endpoint("api/qmusic-watch/announcements").newBuilder()
            .addQueryParameter("versionCode", BuildConfig.VERSION_CODE.toString())
            .build()
        return visibleAnnouncements(get<ControlAnnouncements>(url).items, BuildConfig.VERSION_CODE, System.currentTimeMillis())
    }

    suspend fun config(): RemoteFeatureConfig {
        val url = endpoint("api/qmusic-watch/config").newBuilder()
            .addQueryParameter("versionCode", BuildConfig.VERSION_CODE.toString())
            .build()
        return get<RemoteFeatureConfig>(url).let { value ->
            value.copy(
                features = value.features.filterKeys { it in SUPPORTED_FEATURES },
                messages = value.messages.filterKeys { it in SUPPORTED_FEATURES }.mapValues { it.value.take(160) },
                ttlSeconds = value.ttlSeconds.coerceIn(300, 86_400),
            )
        }
    }

    suspend fun uploadDiagnostics(payload: DiagnosticUpload): DiagnosticReceipt {
        val bytes = json.encodeToString(payload).encodeToByteArray()
        require(bytes.size <= MAX_DIAGNOSTIC_REQUEST_BYTES) { "诊断信息超过 64 KB" }
        return post(endpoint("api/qmusic-watch/diagnostics"), bytes)
    }

    fun download(request: Request): Response = http.newCall(request).execute()

    fun downloadRequest(url: String, rangeStart: Long = 0): Request {
        require(trustedDownloadUrl(url)) { "安装包下载地址不受信任" }
        return Request.Builder().url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", userAgent())
            .apply { if (rangeStart > 0) header("Range", "bytes=$rangeStart-") }
            .build()
    }

    private suspend inline fun <reified T> get(url: HttpUrl): T = request(Request.Builder().url(url).get().build())

    private suspend inline fun <reified T> post(url: HttpUrl, bytes: ByteArray): T = request(
        Request.Builder().url(url).post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build(),
    )

    private suspend inline fun <reified T> request(request: Request): T = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val path = request.url.encodedPath
        http.newCall(request.newBuilder().header("Accept", "application/json").header("User-Agent", userAgent()).build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.use(::readBounded) ?: ByteArray(0)
            AppLog.write("CONTROL", "$path http=${response.code} ms=${System.currentTimeMillis() - started}")
            if (!response.isSuccessful) throw IllegalStateException("控制面响应 ${response.code}")
            val envelope = runCatching { json.decodeFromString<ControlApiResult<T>>(bytes.decodeToString()) }
                .getOrElse { throw IllegalStateException("控制面响应格式无效") }
            if (!envelope.ok) throw IllegalStateException(envelope.error?.message?.take(160).orEmpty().ifBlank { "控制面拒绝请求" })
            envelope.data ?: throw IllegalStateException("控制面响应缺少数据")
        }
    }

    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()
    private fun userAgent() = "QMusicWatch/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; Android ${android.os.Build.VERSION.SDK_INT})"

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_JSON_RESPONSE_BYTES) { "控制面响应过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        val SUPPORTED_FEATURES = setOf("qrLogin", "profile", "lyrics", "stream", "playlistWrites", "diagnostics")
    }
}
