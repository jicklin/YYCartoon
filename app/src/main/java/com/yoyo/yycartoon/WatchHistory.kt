package com.yoyo.yycartoon

import android.content.SharedPreferences
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchHistory(private val preferences: SharedPreferences) {

    fun getDailyCount(): Int {
        rolloverDailyState()
        return preferences.getInt(DAILY_COUNT, 0)
    }

    fun getDailyLimit(): Int = preferences.getInt(DAILY_LIMIT, DEFAULT_DAILY_LIMIT)

    fun setDailyLimit(limit: Int) {
        preferences.edit().putInt(DAILY_LIMIT, limit.coerceIn(1, MAX_DAILY_LIMIT)).apply()
    }

    fun getDailyRemaining(): Int = (getDailyLimit() - getDailyCount()).coerceAtLeast(0)

    fun registerEpisodeStarted(video: VideoItem): Int {
        rolloverDailyState()
        val countedKey = DAILY_COUNTED_PREFIX + stableKey(video)
        if (preferences.getBoolean(countedKey, false)) {
            return getDailyCount()
        }
        val nextCount = getDailyCount() + 1
        preferences.edit()
            .putInt(DAILY_COUNT, nextCount)
            .putBoolean(countedKey, true)
            .apply()
        return nextCount
    }

    fun resetToday() {
        rolloverDailyState()
        val edit = preferences.edit().putInt(DAILY_COUNT, 0)
        preferences.all.keys.forEach { key ->
            if (key.startsWith(DAILY_COUNTED_PREFIX)) {
                edit.remove(key)
            }
        }
        edit.apply()
    }

    fun markWatched(video: VideoItem) {
        preferences.edit()
            .putLong(WATCHED_PREFIX + stableKey(video), System.currentTimeMillis())
            .apply()
    }

    fun isWatched(video: VideoItem): Boolean {
        return preferences.contains(WATCHED_PREFIX + stableKey(video))
    }

    fun getWatchedCount(): Int = preferences.all.keys.count { it.startsWith(WATCHED_PREFIX) }

    fun clearWatchedRecordsAndProgress() {
        val edit = preferences.edit()
        preferences.all.keys.forEach { key ->
            if (key.startsWith(WATCHED_PREFIX) || key.startsWith(PROGRESS_PREFIX)) {
                edit.remove(key)
            }
        }
        edit.apply()
    }

    fun getSavedPosition(video: VideoItem): Int {
        return preferences.getInt(PROGRESS_PREFIX + stableKey(video), 0)
    }

    fun savePosition(video: VideoItem, position: Int, duration: Int) {
        val key = PROGRESS_PREFIX + stableKey(video)
        val canSave = position > MIN_SAVED_POSITION &&
            duration > MIN_SAVED_POSITION &&
            position < duration - MIN_SAVED_POSITION
        if (canSave) {
            preferences.edit().putInt(key, position).apply()
        } else {
            preferences.edit().remove(key).apply()
        }
    }

    fun clearPosition(video: VideoItem) {
        preferences.edit().remove(PROGRESS_PREFIX + stableKey(video)).apply()
    }

    private fun rolloverDailyState() {
        val today = today()
        if (preferences.getString(DAILY_DATE, null) == today) return
        val edit = preferences.edit()
            .putString(DAILY_DATE, today)
            .putInt(DAILY_COUNT, 0)
        preferences.all.keys.forEach { key ->
            if (key.startsWith(DAILY_COUNTED_PREFIX)) {
                edit.remove(key)
            }
        }
        edit.apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    private fun stableKey(video: VideoItem): String {
        val raw = video.uri.toString() + "|" + video.displayName
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DAILY_DATE = "watch_daily_date"
        private const val DAILY_COUNT = "watch_daily_count"
        private const val DAILY_LIMIT = "watch_daily_limit"
        private const val DAILY_COUNTED_PREFIX = "watch_daily_counted_"
        private const val WATCHED_PREFIX = "watch_watched_"
        private const val PROGRESS_PREFIX = "watch_progress_"
        private const val MIN_SAVED_POSITION = 5_000
        const val DEFAULT_DAILY_LIMIT = 4
        const val MAX_DAILY_LIMIT = 12
    }
}
