package com.ronan.qmusicwatch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ronan.qmusicwatch.model.SessionTokens
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json
    private val alias = "qmusic-watch-session"
    private val lock = Any()
    @Volatile private var cached: SessionTokens? = null
    @Volatile private var cacheValid = false

    fun save(tokens: SessionTokens) = synchronized(lock) {
        saveLocked(tokens)
    }

    /** Atomically rotates a cookie only if the session is still the expected one. */
    fun updateCookieIfCurrent(expectedCookie: String, refreshedCookie: String): Boolean = synchronized(lock) {
        if (expectedCookie.isBlank() || refreshedCookie.isBlank()) return@synchronized false
        val current = loadLocked() ?: return@synchronized false
        if (current.upstreamCookie != expectedCookie) return@synchronized false
        saveLocked(current.copy(upstreamCookie = refreshedCookie))
        true
    }

    private fun saveLocked(tokens: SessionTokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.encodeToString(SessionTokens.serializer(), tokens).encodeToByteArray())
        check(
            preferences.edit().putString("value", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit(),
        ) { "会话保存失败" }
        cached = tokens
        cacheValid = true
    }

    /**
     * Memoized read: cookie() is consulted several times per request path, and
     * every miss previously ran a full AndroidKeyStore AES-GCM decrypt (Binder
     * IPC + GCM math) on the caller's thread.
     */
    fun load(): SessionTokens? = synchronized(lock) {
        loadLocked()
    }

    private fun loadLocked(): SessionTokens? {
        if (cacheValid) return cached
        if (!preferences.contains("value")) return null
        val tokens = runCatching {
            val packed = Base64.decode(preferences.getString("value", null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12))) }
            json.decodeFromString(SessionTokens.serializer(), cipher.doFinal(packed.copyOfRange(12, packed.size)).decodeToString())
        }.getOrNull()
        // Do not cache a failed decrypt: a transient AndroidKeyStore outage
        // would otherwise lock the session out until the next save.
        if (tokens != null) {
            cached = tokens
            cacheValid = true
        }
        return tokens
    }

    fun clear() = synchronized(lock) {
        check(preferences.edit().clear().commit()) { "会话清除失败" }
        cached = null
        cacheValid = true
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
