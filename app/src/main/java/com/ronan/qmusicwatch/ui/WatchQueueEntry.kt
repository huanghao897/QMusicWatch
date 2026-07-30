package com.ronan.qmusicwatch.ui

import androidx.compose.runtime.Immutable
import com.ronan.qmusicwatch.model.Track

@Immutable
data class WatchQueueEntry(
    val stableKey: String,
    val track: Track,
)

internal fun stableQueueEntries(tracks: List<Track>): List<WatchQueueEntry> {
    val occurrences = mutableMapOf<String, Int>()
    return tracks.map { track ->
        val occurrence = occurrences.getOrDefault(track.id, 0)
        occurrences[track.id] = occurrence + 1
        WatchQueueEntry("${track.id}#$occurrence", track)
    }
}

internal fun moveQueueEntry(
    entries: List<WatchQueueEntry>,
    fromIndex: Int,
    toIndex: Int,
): List<WatchQueueEntry> {
    if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
        return entries
    }
    return entries.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
