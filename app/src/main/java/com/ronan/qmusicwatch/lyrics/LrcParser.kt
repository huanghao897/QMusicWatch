package com.ronan.qmusicwatch.lyrics

data class LyricWord(val text: String, val startMs: Long, val durationMs: Long)
data class LyricLine(
    val timeMs: Long, val text: String, val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
)

fun highlightedCharacters(line: LyricLine, positionMs: Long, nextLineTimeMs: Long): Int {
    if (positionMs <= line.timeMs) return 0
    if (line.words.isNotEmpty()) {
        var count = 0
        line.words.forEach { word ->
            val end = word.startMs + word.durationMs.coerceAtLeast(1)
            when {
                positionMs >= end -> count += word.text.length
                positionMs > word.startMs -> {
                    val progress = (positionMs - word.startMs).toFloat() / (end - word.startMs)
                    count += kotlin.math.ceil(word.text.length * progress).toInt()
                    return count.coerceIn(0, line.text.length)
                }
                else -> return count.coerceIn(0, line.text.length)
            }
        }
        return count.coerceIn(0, line.text.length)
    }
    val duration = (nextLineTimeMs - line.timeMs).coerceIn(1_000, 8_000)
    val progress = ((positionMs - line.timeMs).toFloat() / duration).coerceIn(0f, 1f)
    return kotlin.math.ceil(line.text.length * progress).toInt().coerceIn(0, line.text.length)
}

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val qrcLine = Regex("\\[(\\d+),(\\d+)](.*)")
    private val qrcWord = Regex("(.*?)\\((\\d+),(\\d+)\\)")
    fun parse(original: String, translation: String? = null, wordSync: String? = null): List<LyricLine> {
        val originalLines = parseQrc(wordSync.orEmpty()).ifEmpty { parseOne(original).map { LyricLine(it.first, it.second) } }
        val translated = parseOne(translation.orEmpty())
        return originalLines.mapIndexed { index, line ->
            val translatedText = translated.firstOrNull { it.first == line.timeMs }?.second
                ?: translated.minByOrNull { kotlin.math.abs(it.first - line.timeMs) }?.takeIf { kotlin.math.abs(it.first - line.timeMs) <= 350 }?.second
                ?: translated.getOrNull(index)?.second.takeIf { translated.size == originalLines.size }
            line.copy(translation = translatedText)
        }.sortedBy { it.timeMs }
    }
    private fun parseQrc(value: String): List<LyricLine> {
        val decoded = value.replace("&#10;", "\n").replace("&quot;", "\"").replace("&amp;", "&")
        val content = Regex("LyricContent=\"(.*?)\"", setOf(RegexOption.DOT_MATCHES_ALL)).find(decoded)?.groupValues?.getOrNull(1) ?: decoded
        return content.lineSequence().mapNotNull { rawLine ->
            val match = qrcLine.find(rawLine.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val tail = match.groupValues[3]
            val words = qrcWord.findAll(tail).map { word ->
                LyricWord(word.groupValues[1], word.groupValues[2].toLong(), word.groupValues[3].toLong())
            }.filter { it.text.isNotEmpty() }.toMutableList()
            if (words.isEmpty()) return@mapNotNull null
            words[0] = words.first().copy(text = words.first().text.trimStart())
            words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
            LyricLine(start, words.joinToString("") { it.text }, words = words)
        }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
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
