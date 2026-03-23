package com.abang.prayerzones.util

import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.NotificationType
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object StressTestMosque {
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    fun createIfEnabled(): Mosque {
        // ✅ Always enabled - controlled by DEBUG_MODE_ENABLED in SharedPreferences
        val now = ZonedDateTime.now()
        val startTime = now.plusMinutes(2)

        return Mosque(
            id = "stress_test_${System.currentTimeMillis()}",
            name = "🔧 StressTEST (3m intervals)",
            displayCity = "Test City",
            displayCountry = "Debug",
            apiCity = "test",
            apiCountry = "debug",
            channelId = "",
            notifType = NotificationType.TTS,
            fajrSound = "azan",
            duhaSound = "duha",
            dhuhrSound = "azan",
            asrSound = "azan",
            maghribSound = "azan",
            ishaSound = "azan",
            timeZone = ZoneId.systemDefault().id,
            latitude = 0.0,
            longitude = 0.0,
            jumuah = null,
            defaultTimings = mapOf(
                "Fajr" to startTime.format(TIME_FORMATTER),
                "Duha" to startTime.plusMinutes(3).format(TIME_FORMATTER),
                "Dhuhr" to startTime.plusMinutes(6).format(TIME_FORMATTER),
                "Asr" to startTime.plusMinutes(9).format(TIME_FORMATTER),
                "Maghrib" to startTime.plusMinutes(12).format(TIME_FORMATTER),
                "Isha" to startTime.plusMinutes(15).format(TIME_FORMATTER)
            )
        )
    }
}
