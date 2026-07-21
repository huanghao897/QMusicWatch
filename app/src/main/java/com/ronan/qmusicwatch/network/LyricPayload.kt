package com.ronan.qmusicwatch.network

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okio.ByteString.Companion.decodeBase64

private val strictBase64 = Regex("[A-Za-z0-9+/]+={0,2}")

/** Decodes QQ Music's Base64 lyric fields without corrupting plain-text fallbacks. */
internal fun decodeQqLyricText(value: String): String {
    if (value.isBlank()) return ""
    val encoded = value.trim()
    if (encoded.length < 8 || encoded.length % 4 != 0 || !strictBase64.matches(encoded)) return value
    val bytes = encoded.decodeBase64()?.toByteArray() ?: return value
    val decoded = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrNull() ?: return value
    if (!decoded.looksLikeLyricText()) return value
    return decoded
}

private fun String.looksLikeLyricText(): Boolean = isNotBlank() && none { character ->
    character.code < 0x20 && character !in setOf('\n', '\r', '\t')
}
