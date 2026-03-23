package com.abang.prayerzones.util

import java.time.LocalTime
import java.time.ZonedDateTime

object LiveWindow {
    /**
     * Returns true if [now] is within [windowMinutes] after [prayerTime] (HH:mm).
     *
     * NOTE: [now] must already be in the mosque's time zone.
     */
    fun isWithinLiveWindow(
        prayerTime: String,
        now: ZonedDateTime,
        windowMinutes: Long = 20L
    ): Boolean {
        val hhmm = prayerTime.trim().take(5)
        val start = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return false

        val startZdt = now.toLocalDate().atTime(start).atZone(now.zone)
        val endZdt = startZdt.plusMinutes(windowMinutes)

        // handle after-midnight by allowing start to match "yesterday" if now is shortly after midnight
        val altStart = startZdt.minusDays(1)
        val altEnd = altStart.plusMinutes(windowMinutes)

        return (now.isEqual(startZdt) || (now.isAfter(startZdt) && now.isBefore(endZdt))) ||
            (now.isEqual(altStart) || (now.isAfter(altStart) && now.isBefore(altEnd)))
    }
}
