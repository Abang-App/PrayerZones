package com.abang.prayerzones.viewmodel

/**
 * Represents the type of prayer, used by the UI to decide on styling.
 */
enum class PrayerType {
    PRIMARY,
    SECONDARY
}

/**
 * A plain data class representing the state of a single prayer row.
 * It contains NO Compose-specific types.
 */
data class PrayerUiState(
    val prayerKey: String,
    val displayName: String,
    val time: String,
    val isNext: Boolean,
    val type: PrayerType,
    val isActive: Boolean = false,
    val isApproximate: Boolean = false,
    val errorMessage: String? = null,
)
