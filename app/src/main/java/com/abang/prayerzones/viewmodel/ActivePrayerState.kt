package com.abang.prayerzones.viewmodel

/**
 * Represents an actively playing prayer state with its key and start time
 */
data class ActivePrayerState(
    val mosqueId: String,
    val prayerKey: String,
    val startTimeMillis: Long
)
