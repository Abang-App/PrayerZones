package com.abang.prayerzones.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * Phase 3: Contextual Audio Signatures
 *
 * Provides prayer-specific intro chimes (attention tones) based on the time of day
 * and spiritual context of each prayer.
 */
object PrayerAudioSignatures {
    private const val TAG = "PrayerAudioSignatures"

    /**
     * Get the appropriate system notification URI for the given prayer.
     *
     * Prayer Audio Mapping:
     * - Fajr: Ascending/Alert (dawn awakening)
     * - Dhuhr/Asr: Steady/Reminder (midday/afternoon)
     * - Maghrib/Isha: Warm/Deep (evening/night)
     */
    fun getIntroChimeForPrayer(context: Context, prayerName: String): Uri {
        return when (prayerName) {
            "Fajr" -> {
                // Dawn prayer - use alert/ascending tone
                try {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get alarm URI for Fajr, using notification", e)
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
            }

            "Dhuhr", "Asr", "Jumu'ah", "Jumu'ah1", "Duha" -> {
                // Midday/afternoon prayers - use steady/reminder tone
                try {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get notification URI, using default", e)
                    getDefaultNotificationUri()
                }
            }

            "Maghrib", "Isha", "Jumu'ah2" -> {
                // Evening/night prayers - use warm/deep tone
                try {
                    // Try to get ringtone for deeper sound
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get ringtone URI for evening prayer, using notification", e)
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
            }

            else -> {
                // Default fallback
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
        }
    }

    /**
     * Play the contextual intro chime for the given prayer
     */
    fun playIntroChime(context: Context, prayerName: String) {
        try {
            val chimeUri = getIntroChimeForPrayer(context, prayerName)
            val ringtone = RingtoneManager.getRingtone(context, chimeUri)

            if (ringtone != null) {
                Log.d(TAG, "Playing intro chime for $prayerName")
                ringtone.play()
            } else {
                Log.w(TAG, "Failed to create ringtone for $prayerName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing intro chime for $prayerName", e)
        }
    }

    /**
     * Get prayer time category for logging/debugging
     */
    fun getPrayerTimeCategory(prayerName: String): String {
        return when (prayerName) {
            "Fajr" -> "Dawn (Ascending/Alert)"
            "Dhuhr", "Asr", "Jumu'ah", "Jumu'ah1", "Duha" -> "Daytime (Steady/Reminder)"
            "Maghrib", "Isha", "Jumu'ah2" -> "Evening/Night (Warm/Deep)"
            else -> "Default"
        }
    }

    private fun getDefaultNotificationUri(): Uri {
        return Uri.parse("android.resource://com.abang.prayerzones/raw/short_tone1")
    }
}
