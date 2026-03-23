package com.abang.prayerzones.util

import android.util.Log
import com.abang.prayerzones.model.Mosque
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.data.DateComponents
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.CalculationParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Offline Prayer Time Calculator using the Adhan library (v1.x).
 *
 * This singleton provides precise prayer time calculations based on:
 * - Mosque coordinates (latitude/longitude)
 * - Mosque timezone
 * - Current date
 *
 * It replaces hardcoded/approximate defaultTimings with accurate calculations.
 */
object OfflinePrayerCalculator {

    private const val TAG = "OfflinePrayerCalc"

    /**
     * Calculate today's prayer times for a specific mosque.
     *
     * @param mosque The mosque to calculate times for
     * @return Map of prayer names to times in "HH:mm" format in the mosque's local timezone.
     *         Returns a safe fallback map if calculation fails.
     */
    fun calculateToday(mosque: Mosque): Map<String, String> {
        return try {
            // 1. Validate coordinates
            if (mosque.latitude !in -90.0..90.0 || mosque.longitude !in -180.0..180.0) {
                Log.w(TAG, "Invalid coordinates for ${mosque.id}: lat=${mosque.latitude}, lon=${mosque.longitude}")
                return getFallbackTimings()
            }

            // 2. Validate timezone
            val zoneId = mosque.validZoneId
            if (zoneId == null) {
                Log.w(TAG, "Invalid timezone for ${mosque.id}: ${mosque.timeZone}")
                return getFallbackTimings()
            }

            // 3. Create coordinates for Adhan library
            val coordinates = Coordinates(mosque.latitude, mosque.longitude)

            // 4. Get today's date in the mosque's timezone
            val calendar = Calendar.getInstance(TimeZone.getTimeZone(mosque.timeZone))
            val dateComponents = DateComponents(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, // Calendar.MONTH is 0-based
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            // 5. Set calculation parameters
            // Use SINGAPORE method for Singapore mosques, MUSLIM_WORLD_LEAGUE for others
            val params: CalculationParameters = if (mosque.id.startsWith("SG")) {
                CalculationMethod.SINGAPORE.parameters
            } else {
                CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
            }

            // Set Shafi madhab for Asr calculation
            params.madhab = Madhab.SHAFI

            // 6. Calculate prayer times
            val prayerTimes = PrayerTimes(coordinates, dateComponents, params)

            // 7. Format times in the mosque's timezone
            val formatter = SimpleDateFormat("HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(mosque.timeZone)
            }

            // 8. Build the result map
            val timings = mutableMapOf<String, String>()

            timings["Fajr"] = formatter.format(prayerTimes.fajr)
            timings["Dhuhr"] = formatter.format(prayerTimes.dhuhr)
            timings["Asr"] = formatter.format(prayerTimes.asr)
            timings["Maghrib"] = formatter.format(prayerTimes.maghrib)
            timings["Isha"] = formatter.format(prayerTimes.isha)

            // 9. Add Duha (fixed at 15 minutes after sunrise)
            val duhaTime = Date(prayerTimes.sunrise.time + 15 * 60 * 1000) // +15 minutes
            timings["Duha"] = formatter.format(duhaTime)

            Log.d(TAG, "✅ Calculated times for ${mosque.id} (${mosque.name}): $timings")

            // Validate we have all required prayers
            if (timings.size < 5) {
                Log.w(TAG, "Incomplete prayer times calculated for ${mosque.id}, using fallback")
                return getFallbackTimings()
            }

            timings

        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate prayer times for ${mosque.id}: ${e.message}", e)
            getFallbackTimings()
        }
    }

    /**
     * Safe fallback timings in case calculation fails.
     * These are approximate times that ensure the app doesn't crash.
     */
    private fun getFallbackTimings(): Map<String, String> {
        return mapOf(
            "Fajr" to "05:30",
            "Duha" to "07:00",
            "Dhuhr" to "13:00",
            "Asr" to "16:30",
            "Maghrib" to "19:00",
            "Isha" to "20:30"
        )
    }
}

