package com.abang.prayerzones.util

import android.util.Log
import com.abang.prayerzones.model.PrayerTimings
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Filters out unwanted prayer times (Sunrise, Sunset, FirstThird, Imsak, Midnight, LastThird)
 * and keeps only the prayers we show in the app in the desired order.
 */
object PrayerFilter {
    // Only keep these 6 prayers in order
    private val displayOrder = listOf("Fajr", "Duha", "Dhuhr", "Asr", "Maghrib", "Isha")

    // Prayers that should NEVER be scheduled or displayed
    private val excludedPrayers = setOf(
        "Sunrise", "Sunset", "Imsak", "Midnight", "Firstthird", "Lastthird"
    )

    /**
     * Filters and sorts timings into a List of Pairs.
     * Output: [("Fajr","05:01"), ("Duha","07:15"), ("Dhuhr","12:45"), ...]
     */
    fun filterAndSort(timings: Map<String, String>): List<Pair<String, String>> {
        return displayOrder.mapNotNull { prayer ->
            timings[prayer]?.let { prayerTime -> prayer to prayerTime }
        }
    }

    /**
     * Filters raw API timings into a clean Map with only displayable prayers.
     * This removes: Sunrise, Sunset, FirstThird, LastThird, Imsak, Midnight
     * INCLUDES Duha calculated from Sunrise time.
     */
    fun filterToMap(rawTimings: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Add core prayers
        rawTimings.forEach { (key, value) ->
            if (key in displayOrder && key != "Duha") { // Duha is handled separately
                result[key] = value
            }
        }

        // Calculate and add Duha from Sunrise (if available)
        val sunriseStr = rawTimings["Sunrise"]
        if (sunriseStr != null) {
            try {
                // Parse sunrise time (handle formats like "06:30" or "06:30 (GMT)")
                val cleanTime = sunriseStr.substring(0, 5) // Take "HH:MM" part
                val sunriseTime = LocalTime.parse(cleanTime)

                // Duha is typically 20-30 minutes after sunrise - adjust as needed
                val duhaTime = sunriseTime.plusMinutes(20) // Example: 20 minutes after sunrise
                result["Duha"] = duhaTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e: Exception) {
                // If we can't parse sunrise, skip Duha
                Log.e("PrayerFilter", "Could not parse sunrise time: $sunriseStr")
            }
        }

        return result
    }

    /**
     * Checks if a prayer should be excluded from all operations
     */
    fun isExcluded(prayerName: String): Boolean {
        return excludedPrayers.contains(prayerName)
    }

    /**
     * Get the ordered list of prayer names (useful for iteration)
     */
    fun getDisplayOrder(): List<String> = displayOrder
}

/**
 * Extension function to convert PrayerTimings object directly to filtered map,
 * INCLUDING Duha calculated from Sunrise time.
 */
fun PrayerTimings.toFilteredMap(): Map<String, String> {
    val map = mutableMapOf<String, String>()

    // Add the core prayers from PrayerTimings
    map["Fajr"] = this.fajr
    map["Dhuhr"] = this.dhuhr
    map["Asr"] = this.asr
    map["Maghrib"] = this.maghrib
    map["Isha"] = this.isha

    // Calculate Duha from Sunrise
    try {
        val sunriseTime = LocalTime.parse(this.sunrise.substring(0, 5))
        val duhaTime = sunriseTime.plusMinutes(20) // Adjust based on your calculation
        map["Duha"] = duhaTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (e: Exception) {
        Log.e("PrayerTimings", "Could not calculate Duha from sunrise: ${this.sunrise}")
    }

    return map
}