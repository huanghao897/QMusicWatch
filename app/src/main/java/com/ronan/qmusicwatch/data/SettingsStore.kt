package com.ronan.qmusicwatch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val qualityKey = stringPreferencesKey("quality")
    private val headphoneWarningKey = booleanPreferencesKey("headphone_warning")
    private val autoOpenPlayerKey = booleanPreferencesKey("auto_open_player")
    private val playModeKey = stringPreferencesKey("play_mode")
    private val lyricSizeKey = stringPreferencesKey("lyric_size")
    private val lyricTranslationKey = booleanPreferencesKey("lyric_translation")
    private val lyricOffsetKey = stringPreferencesKey("lyric_offset")
    private val pureBlackKey = booleanPreferencesKey("pure_black")
    private val playbackSnapshotKey = stringPreferencesKey("playback_snapshot")
    private val dailyCountKey = stringPreferencesKey("daily_count")
    private val searchHistoryKey = stringPreferencesKey("search_history")
    val quality = context.settingsDataStore.data.map { it[qualityKey] ?: "128" }
    val headphoneWarning = context.settingsDataStore.data.map { it[headphoneWarningKey] ?: true }
    val autoOpenPlayer = context.settingsDataStore.data.map { it[autoOpenPlayerKey] ?: true }
    val playMode = context.settingsDataStore.data.map { it[playModeKey] ?: "sequential" }
    val lyricSize = context.settingsDataStore.data.map { it[lyricSizeKey] ?: "normal" }
    val lyricTranslation = context.settingsDataStore.data.map { it[lyricTranslationKey] ?: true }
    val lyricOffset = context.settingsDataStore.data.map { it[lyricOffsetKey]?.toLongOrNull() ?: 0L }
    val pureBlack = context.settingsDataStore.data.map { it[pureBlackKey] ?: false }
    val playbackSnapshot = context.settingsDataStore.data.map { it[playbackSnapshotKey].orEmpty() }
    val dailyCount = context.settingsDataStore.data.map { if (it[dailyCountKey] == "10") 10 else 5 }
    val searchHistory = context.settingsDataStore.data.map { it[searchHistoryKey].orEmpty().lineSequence().filter(String::isNotBlank).take(8).toList() }
    suspend fun setQuality(value: String) = context.settingsDataStore.edit { it[qualityKey] = if (value == "320") "320" else "128" }
    suspend fun setHeadphoneWarning(value: Boolean) = context.settingsDataStore.edit { it[headphoneWarningKey] = value }
    suspend fun setAutoOpenPlayer(value: Boolean) = context.settingsDataStore.edit { it[autoOpenPlayerKey] = value }
    suspend fun setPlayMode(value: String) = context.settingsDataStore.edit { it[playModeKey] = value }
    suspend fun setLyricSize(value: String) = context.settingsDataStore.edit { it[lyricSizeKey] = value }
    suspend fun setLyricTranslation(value: Boolean) = context.settingsDataStore.edit { it[lyricTranslationKey] = value }
    suspend fun setLyricOffset(value: Long) = context.settingsDataStore.edit { it[lyricOffsetKey] = value.coerceIn(-10_000, 10_000).toString() }
    suspend fun setPureBlack(value: Boolean) = context.settingsDataStore.edit { it[pureBlackKey] = value }
    suspend fun setPlaybackSnapshot(value: String) = context.settingsDataStore.edit { it[playbackSnapshotKey] = value }
    suspend fun setDailyCount(value: Int) = context.settingsDataStore.edit { it[dailyCountKey] = if (value == 10) "10" else "5" }
    suspend fun addSearchHistory(value: String) = context.settingsDataStore.edit { prefs ->
        val query = value.replace('\n', ' ').trim().take(80)
        if (query.isNotBlank()) prefs[searchHistoryKey] = (listOf(query) + prefs[searchHistoryKey].orEmpty().lineSequence().filter { it.isNotBlank() && it != query }).take(8).joinToString("\n")
    }
    suspend fun clearSearchHistory() = context.settingsDataStore.edit { it.remove(searchHistoryKey) }
}
