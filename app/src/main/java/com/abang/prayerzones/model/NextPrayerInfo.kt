package com.abang.prayerzones.model

/**
 * Represents the next upcoming prayer information for a given mosque.
 * @param name The name of the next prayer (e.g., "Dhuhr", "Asr", "Duha", "Jumu'ah1").
 * @param secondsUntil The number of seconds remaining until that prayer.
 * @param displayTime Optional human-readable time (e.g., "13:10").
 * @param mosqueName Optional mosque reference for multi-mosque scenarios.
 */
data class NextPrayerInfo(
    val name: String,
    val secondsUntil: Long,
    val displayTime: String? = null,
    val mosqueName: String? = null
)
