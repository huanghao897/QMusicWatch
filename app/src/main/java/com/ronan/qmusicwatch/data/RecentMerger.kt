package com.ronan.qmusicwatch.data

import com.ronan.qmusicwatch.model.Track

fun mergeRecent(local: List<Track>, cloud: List<Track>): List<Track> {
    val localIds = local.asSequence().map { it.id }.toHashSet()
    val timestamped = (local + cloud.filter { it.playedAt != null })
        .groupBy { it.id }.map { (_, items) -> items.maxBy { it.playedAt ?: Long.MIN_VALUE } }
        .sortedByDescending { it.playedAt }
    val known = timestamped.asSequence().map { it.id }.toHashSet().apply { addAll(localIds) }
    return timestamped + cloud.filter { it.playedAt == null && it.id !in known }.distinctBy { it.id }
}

