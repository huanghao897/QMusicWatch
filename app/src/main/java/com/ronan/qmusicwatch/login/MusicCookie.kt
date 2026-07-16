package com.ronan.qmusicwatch.login

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object MusicCookie {
    fun accountId(cookie: String): String? = cookie.split(';')
        .mapNotNull { part -> part.trim().split('=', limit = 2).takeIf { it.size == 2 } }
        .firstOrNull { it[0] in setOf("qqmusic_uin", "uin", "wxuin") }
        ?.get(1)?.removePrefix("o")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }

    fun provider(cookie: String, fallback: String): String = cookie.split(';')
        .mapNotNull { part -> part.trim().split('=', limit = 2).takeIf { it.size == 2 } }
        .firstOrNull { it[0] == "tmeLoginType" }
        ?.get(1)?.let { when (it) { "1" -> "wechat"; "2" -> "qq"; else -> null } }
        ?: fallback.takeIf { it == "qq" || it == "wechat" } ?: "qq"

    fun fromQrMessage(message: String): String? = runCatching {
        val cookies = Json.parseToJsonElement(message).jsonObject["cookies"]?.jsonObject ?: return null
        listOf("qqmusic_key", "qqmusic_uin", "qrcode_id").map { name ->
            val raw = cookies[name]
            val value = when (raw) {
                is JsonPrimitive -> raw.contentOrNull
                is JsonObject -> (raw["value"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() && it.length <= 4096 && ';' !in it && '\r' !in it && '\n' !in it } ?: return null
            "$name=$value"
        }.joinToString("; ")
    }.getOrNull()
}
