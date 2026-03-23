package com.abang.prayerzones

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CachedPrayerTimes(
    val timings: Map<String, String>,
    val lastUpdated: Long // epoch millis
)

class PrayerCache(context: Context) {
    private val prefs = context.getSharedPreferences("prayer_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Load both timings and lastUpdated
    fun loadCachedPrayerTimes(mosqueId: String): CachedPrayerTimes? {
        val json = prefs.getString(keyTimings(mosqueId), null) ?: return null
        val lastUpdated = prefs.getLong(keyUpdated(mosqueId), 0L)
        val timings: Map<String, String> =
            gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type)
        return CachedPrayerTimes(timings, lastUpdated)
    }

    // Convenience: load just the timings map
    fun loadTimings(mosqueId: String): Map<String, String>? {
        return loadCachedPrayerTimes(mosqueId)?.timings
    }

    // Save timings with current timestamp
    fun saveTimings(mosqueId: String, timings: Map<String, String>) {
        val json = gson.toJson(timings)
        val now = System.currentTimeMillis()
        prefs.edit {
            putString(keyTimings(mosqueId), json)
            putLong(keyUpdated(mosqueId), now)
        }
    }

    /**
     * Clear all cached prayer times
     * Useful for forcing refresh or clearing stale data
     */
    fun clearAllCache() {
        prefs.edit { clear() }
    }

    private fun keyTimings(id: String) = "timings_$id"
    private fun keyUpdated(id: String) = "updated_$id"
}

