package com.ronan.qmusicwatch.lyrics

data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    fun parse(original: String, translation: String? = null): List<LyricLine> {
        val originalLines = parseOne(original)
        val translated = parseOne(translation.orEmpty())
        return originalLines.mapIndexed { index, (time, text) ->
            val translatedText = translated.firstOrNull { it.first == time }?.second
                ?: translated.minByOrNull { kotlin.math.abs(it.first - time) }?.takeIf { kotlin.math.abs(it.first - time) <= 350 }?.second
                ?: translated.getOrNull(index)?.second.takeIf { translated.size == originalLines.size }
            LyricLine(time, text, translatedText)
        }.sortedBy { it.timeMs }
    }
    private fun parseOne(value: String): List<Pair<Long, String>> = value.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val text = stamp.replace(line, "").trim()
        matches.asSequence().map { match ->
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            (match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 + fraction) to text
        }
    }.filter { it.second.isNotBlank() }.toList()
}
