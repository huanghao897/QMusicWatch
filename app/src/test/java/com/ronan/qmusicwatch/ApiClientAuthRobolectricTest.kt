package com.ronan.qmusicwatch

import android.app.Application
import com.ronan.qmusicwatch.model.Track
import com.ronan.qmusicwatch.network.ApiClient
import com.ronan.qmusicwatch.network.QqBusinessException
import com.ronan.qmusicwatch.network.QqCredentialExpiredException
import com.ronan.qmusicwatch.network.QqHttpException
import com.ronan.qmusicwatch.network.requireSameQqAccount
import com.ronan.qmusicwatch.network.requiresNewQrLogin
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic transport only: no real QQ Music, control-plane or Keystore access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApiClientAuthRobolectricTest {
    private val staleCookie = "uin=o12345; qqmusic_uin=12345; qm_keyst=old-key; tmeLoginType=2; " +
        "wxopenid=open-id; access_token=access-token; refresh_token=refresh-token; refresh_key=refresh-key"
    private val otherCookie = "uin=o67890; qqmusic_uin=67890; qm_keyst=other-account-key"
    private val track = Track("00485V8K4InqbZ", "Synthetic song", numericId = 42, mediaMid = "media-mid")

    private data class Call(val request: Request, val key: String, val body: JsonObject) {
        val method get() = body["method"]!!.jsonPrimitive.content
        val param get() = body["param"]!!.jsonObject
    }

    private fun api(
        session: AtomicReference<String?>,
        persist: (String, String) -> Boolean = { stale, refreshed -> session.compareAndSet(stale, refreshed) },
        respond: (Call) -> Response,
    ): ApiClient {
        val client = ApiClient(RuntimeEnvironment.getApplication(), cookie = session::get, updateCookie = persist)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = Json.parseToJsonElement(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).jsonObject
            val key = payload.keys.single { it != "comm" }
            respond(Call(request, key, payload[key]!!.jsonObject))
        }.build()
        client.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(client, http)
        return client
    }

    private fun reply(call: Call, code: Int = 0, data: JsonObject = JsonObject(emptyMap()), status: Int = 200): Response {
        val payload = buildJsonObject {
            put("code", 0)
            put(call.key, buildJsonObject { put("code", code); put("data", data) })
        }
        return Response.Builder().request(call.request).protocol(Protocol.HTTP_1_1)
            .code(status).message("Synthetic fixture")
            .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
    }

    private fun refreshedData() = buildJsonObject {
        put("musicid", "12345")
        put("musickey", "new-key")
        put("refresh_token", "new-refresh-token")
    }

    @Test fun normalizedWriteCookiesRefreshAndPersistAgainstTheRawSession() = runBlocking {
        val session = AtomicReference<String?>(staleCookie)
        val methods = mutableListOf<String>()
        val writeCookies = mutableListOf<String>()
        var persistedAgainst: String? = null
        val client = api(session, persist = { expected, refreshed ->
            persistedAgainst = expected
            session.compareAndSet(expected, refreshed)
        }) { call ->
            methods += call.method
            if (call.method == "Login") reply(call, data = refreshedData()) else {
                writeCookies += call.request.header("Cookie").orEmpty()
                reply(call, code = if (writeCookies.size == 1) 104400 else 0)
            }
        }

        assertTrue(client.like(track, true).accepted)
        assertEquals(listOf("AddSongFav", "Login", "AddSongFav"), methods)
        assertEquals(staleCookie, persistedAgainst)
        assertTrue(writeCookies.first().contains("loginUin=12345"))
        assertTrue(writeCookies.first().contains("musickey=old-key"))
        assertTrue(writeCookies.last().contains("loginUin=12345"))
        assertTrue(writeCookies.last().contains("musickey=new-key"))
        assertTrue(session.get()!!.contains("refresh_token=new-refresh-token"))
    }

    @Test fun accountSwitchOrLogoutCannotReplayAnExpiredWriteWithAnotherSession() {
        for (replacement in listOf(otherCookie, null)) {
            val session = AtomicReference<String?>(staleCookie)
            val methods = mutableListOf<String>()
            val client = api(session) { call ->
                methods += call.method
                session.set(replacement)
                reply(call, code = 104400)
            }
            assertThrows(CancellationException::class.java) { runBlocking { client.like(track, true) } }
            assertEquals(listOf("AddSongFav"), methods)
            assertEquals(replacement, session.get())
        }
    }

    @Test fun sameAccountRotationMayReplayWithoutASecondRefresh() = runBlocking {
        val session = AtomicReference<String?>(staleCookie)
        val rotated = staleCookie.replace("old-key", "rotated-key")
        val writeCookies = mutableListOf<String>()
        val client = api(session) { call ->
            assertEquals("AddSongFav", call.method)
            writeCookies += call.request.header("Cookie").orEmpty()
            if (writeCookies.size == 1) session.set(rotated)
            reply(call, code = if (writeCookies.size == 1) 104400 else 0)
        }
        assertTrue(client.like(track, true).accepted)
        assertEquals(2, writeCookies.size)
        assertTrue(writeCookies.last().contains("musickey=rotated-key"))
    }

    @Test fun aSwitchDuringRefreshCannotPersistOrReplayTheOldAccount() {
        val session = AtomicReference<String?>(staleCookie)
        val methods = mutableListOf<String>()
        var persistenceCalls = 0
        val client = api(session, persist = { _, _ -> persistenceCalls++; true }) { call ->
            methods += call.method
            if (call.method == "Login") {
                session.set(otherCookie)
                reply(call, data = refreshedData())
            } else reply(call, code = 104400)
        }
        assertThrows(CancellationException::class.java) { runBlocking { client.like(track, true) } }
        assertEquals(listOf("AddSongFav", "Login"), methods)
        assertEquals(0, persistenceCalls)
        assertEquals(otherCookie, session.get())
    }

    @Test fun refreshIoFailuresKeepTheirNetworkClassification() {
        val session = AtomicReference<String?>(staleCookie)
        val client = api(session) { call ->
            if (call.method == "Login") throw IOException("Synthetic temporary outage")
            reply(call, code = 104400)
        }
        val failure = assertThrows(IOException::class.java) { runBlocking { client.like(track, true) } }
        assertFalse(requiresNewQrLogin(failure))
        assertEquals(staleCookie, session.get())
    }

    @Test fun refreshRateLimitAndServerFailuresAreNotCredentialExpiry() {
        for (status in listOf(429, 500, 502, 503, 504)) {
            val session = AtomicReference<String?>(staleCookie)
            var refreshCalls = 0
            val client = api(session) { call ->
                if (call.method == "Login") {
                    refreshCalls++
                    reply(call, status = status)
                } else reply(call, code = 104400)
            }
            val failure = assertThrows(QqHttpException::class.java) { runBlocking { client.like(track, true) } }
            assertEquals(status, failure.statusCode)
            assertFalse(requiresNewQrLogin(failure))
            assertEquals(1, refreshCalls)
            assertEquals(staleCookie, session.get())
        }
    }

    @Test fun anUnrelatedLoginBusinessFailureIsNotCredentialExpiry() {
        val session = AtomicReference<String?>(staleCookie)
        val client = api(session) { call -> reply(call, code = if (call.method == "Login") 80050 else 104400) }
        val failure = assertThrows(QqBusinessException::class.java) { runBlocking { client.like(track, true) } }
        assertEquals(80050, failure.businessCode)
        assertFalse(requiresNewQrLogin(failure))
    }

    @Test fun explicitLoginCredentialRejectionStillRequiresQrLogin() {
        val session = AtomicReference<String?>(staleCookie)
        val client = api(session) { call -> reply(call, code = if (call.method == "Login") 104401 else 104400) }
        assertThrows(QqCredentialExpiredException::class.java) { runBlocking { client.like(track, true) } }
    }

    @Test fun failedPersistenceDoesNotPretendTheAccountWasRejected() {
        val session = AtomicReference<String?>(staleCookie)
        val client = api(session, persist = { _, _ -> false }) { call ->
            if (call.method == "Login") reply(call, data = refreshedData()) else reply(call, code = 104400)
        }
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { client.like(track, true) } }
        assertFalse(requiresNewQrLogin(failure))
        assertEquals(staleCookie, session.get())
    }

    @Test fun aTransientCredentialProbeFailureDoesNotBlockStandardQuality() = runBlocking {
        val session = AtomicReference<String?>(staleCookie)
        var probeCalls = 0
        val client = api(session) { call ->
            when (call.method) {
                "GetLoginUserInfo" -> {
                    probeCalls++
                    reply(call, status = 503)
                }
                "CgiGetVkey", "UrlGetVkey" -> {
                    if (call.param["filename"].toString().contains("M500")) {
                        reply(call, data = Json.parseToJsonElement("""{"midurlinfo":[{"purl":"M500media-mid.mp3?vkey=synthetic"}],"sip":["https://dl.stream.qqmusic.qq.com/"]}""").jsonObject)
                    } else reply(call)
                }
                else -> error("Unexpected synthetic request ${call.method}")
            }
        }
        val stream = client.stream(track, "hq")
        assertEquals("standard", stream.quality)
        assertTrue(stream.url.startsWith("https://dl.stream.qqmusic.qq.com/M500"))
        assertEquals(2, probeCalls)
    }

    @Test fun aCancelledCredentialProbeCannotContinueQualityFallback() {
        val session = AtomicReference<String?>(staleCookie)
        var standardRequests = 0
        val client = api(session) { call ->
            if (call.method == "GetLoginUserInfo") throw CancellationException("Synthetic cancellation")
            if (call.param["filename"].toString().contains("M500")) standardRequests++
            reply(call)
        }
        assertThrows(CancellationException::class.java) { runBlocking { client.stream(track, "hq") } }
        assertEquals(0, standardRequests)
    }

    @Test fun explicitCredentialExpiryDuringAProbeCannotContinueQualityFallback() {
        val session = AtomicReference<String?>("qqmusic_uin=12345; qm_keyst=old-key")
        var standardRequests = 0
        val client = api(session) { call ->
            if (call.param["filename"].toString().contains("M500")) standardRequests++
            reply(call, code = if (call.method == "GetLoginUserInfo") 1000 else 0)
        }
        assertThrows(QqCredentialExpiredException::class.java) { runBlocking { client.stream(track, "hq") } }
        assertEquals(0, standardRequests)
    }

    @Test fun accountGuardAllowsRotationButRejectsAnotherAccountOrLogout() {
        requireSameQqAccount(staleCookie, staleCookie.replace("old-key", "rotated-key"))
        assertThrows(CancellationException::class.java) { requireSameQqAccount(staleCookie, otherCookie) }
        assertThrows(CancellationException::class.java) { requireSameQqAccount(staleCookie, null) }
    }
}
