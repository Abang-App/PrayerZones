package com.abang.prayerzones.model

/**
 * Audio profiles available in "Auto In-Mosque Mode".
 *
 * Simplified to 4 essential modes:
 *   Disabled       — Feature OFF (manual control)
 *   Silent        — RINGER_MODE_SILENT
 *   Vibrate only   — RINGER_MODE_VIBRATE
 *   Lower Volume   — Reduces volume by configurable % (respects prayer alarms)
 */
enum class InMosqueMode(
    val prefValue: String,
    val label: String,
    val emoji: String
) {
    DISABLED     ("disabled",      "Disabled",        "🔔"),
    SILENT       ("silent",        "Silent",         "🔇"),
    VIBRATE      ("vibrate",       "Vibrate only",    "📳"),
    LOWER_VOLUME ("lower_volume",  "Lower Volume",    "🔉");

    companion object {
        fun fromPref(value: String?): InMosqueMode =
            entries.firstOrNull { it.prefValue == value } ?: DISABLED

        // ── Preference keys ──────────────────────────────────────────
        const val PREF_KEY          = "pref_in_mosque_mode"
        const val PREF_ACTIVE       = "in_mosque_mode_active"
        const val PREF_SAVED_RINGER = "in_mosque_saved_ringer_mode"
        const val PREF_MOSQUE_NAME  = "in_mosque_current_name"
        const val PREF_MOSQUE_ID    = "in_mosque_current_id"
        const val PREF_SAVED_RING_VOL = "in_mosque_saved_ring_vol"
        const val PREF_SAVED_NOTIF_VOL = "in_mosque_saved_notif_vol"
        const val PREF_SAVED_MUSIC_VOL = "in_mosque_saved_music_vol"

        /** Alias used by UI layer to check the "Still at Mosque?" card flag. */
        const val PREF_SHOW_STAY_REMINDER = "in_mosque_show_stay_card"

        /** Last computed distance (metres) from the user to the active mosque. Written
         *  by InMosqueModeManager / GeofenceManager after every location fix. */
        const val PREF_LAST_DISTANCE = "in_mosque_last_distance"

        // ── Centralized defaults (single source of truth for Kotlin) ─
        const val DEFAULT_NEAR_PRAYER_MIN    = "25"
        const val DEFAULT_MIN_DISTANCE_METERS = "30"
        const val DEFAULT_LOWER_VOLUME_PCT   = "15"
    }
}
