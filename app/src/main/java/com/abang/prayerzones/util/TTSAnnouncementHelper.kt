package com.abang.prayerzones.util

import android.content.Context
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import com.abang.prayerzones.datastore.prayerSettingsDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Phase 3: TTS Announcement Helper
 *
 * Handles Text-to-Speech announcements for secondary mosque notifications
 * with multi-language support and contextual audio signatures.
 */
object TTSAnnouncementHelper {
    private const val TAG = "TTSAnnouncementHelper"

    private fun translatePrayerName(prayerName: String, languageCode: String): String {
        val key = prayerName.trim()
        return when (languageCode) {
            "fr" -> when (key) {
                "Fajr" -> "Fadjr"           // The 'd' helps the French voice pronounce the 'j' (soft g) correctly.
                "Duha" -> "Douha"           // In French, 'u' is a sharp 'y' sound; 'ou' produces the correct 'u' sound.
                "Dhuhr" -> "Dhour"          // French voices struggle with 'h'. This makes it sound like 'Zohr/Dohr'.
                "Asr" -> "Asr"              // Standard spelling works well in French.
                "Maghrib" -> "Maghreb"      // This is the standard French spelling everyone recognizes.
                "Isha" -> "Icha"            // In French, 'sh' is often 'ch' (as in 'chat'). 'Icha' sounds perfect.
                else -> key
            }
            "in", "id" -> when (key) {
                "Fajr" -> "Subuh"
                "Duha" -> "Dhuha"
                "Dhuhr" -> "Dzuhur"
                "Asr" -> "Asar"
                "Maghrib" -> "Maghrib"
                "Isha" -> "Isya"
                else -> key
            }
            "ar" -> when (key) {
                "Fajr" -> "الفجر"
                "Duha" -> "الضحى"
                "Dhuhr" -> "الظهر"
                "Asr" -> "العصر"
                "Maghrib" -> "المغرب"
                "Isha" -> "العشاء"
                else -> key
            }
            "fa" -> when (key) {
                "Fajr" -> "صبح"
                "Duha" -> "ضحی"
                "Dhuhr" -> "ظهر"
                "Asr" -> "عصر"
                "Maghrib" -> "مغرب"
                "Isha" -> "عشاء"
                else -> key
            }
            "ur" -> when (key) {
                "Fajr" -> "فجر"
                "Duha" -> "چاشت"
                "Dhuhr" -> "ظہر"
                "Asr" -> "عصر"
                "Maghrib" -> "مغرب"
                "Isha" -> "عشاء"
                else -> key
            }
            "hi" -> when (key) {
                "Fajr" -> "फ़ज्र"
                "Duha" -> "दुहा"
                "Dhuhr" -> "ज़ुहर"
                "Asr" -> "असर"
                "Maghrib" -> "मगरिब"
                "Isha" -> "ईशा"
                else -> key
            }
            "bn" -> when (key) {
                "Fajr" -> "ফজর"
                "Duha" -> "দুহা"
                "Dhuhr" -> "যোহর"
                "Asr" -> "আসর"
                "Maghrib" -> "মাগরিব"
                "Isha" -> "ইশা"
                else -> key
            }
            else -> key
        }
    }

    /**
     * Get the translated announcement text for a prayer at a specific mosque
     *
     * Template: "[Prayer] at [Mosque]" in the user's selected language
     */
    fun getAnnouncementText(prayerName: String, mosqueName: String, languageCode: String): String {
        return TranslationRegistry.getAnnouncement(
            prayerName = translatePrayerName(prayerName, languageCode),
            mosqueName = mosqueName,
            languageKey = languageCode
        )
    }

    /**
     * Get the user's selected TTS language from DataStore
     */
    fun getTTSLanguage(context: Context): String {
        return try {
            runBlocking {
                context.prayerSettingsDataStore.data.first()[
                    stringPreferencesKey("pref_tts_language")
                ] ?: "en_US"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get TTS language, using default", e)
            "en_US"
        }
    }

    /**
     * Get TTS speech speed from DataStore (50-200%, default 100%)
     */
    fun getTTSSpeed(context: Context): Float {
        return try {
            runBlocking {
                val speedValue = context.prayerSettingsDataStore.data.first()[
                    androidx.datastore.preferences.core.intPreferencesKey("pref_tts_speed")
                ] ?: 100
                speedValue / 100f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get TTS speed, using default", e)
            1.0f
        }
    }

    /**
     * Convert language code to Locale object
     */
    fun getLocaleFromLanguageCode(languageCode: String): Locale {
        return when (languageCode) {
            "en_US" -> Locale.US
            "en_GB" -> Locale.UK
            "ar" -> Locale("ar")
            "bn" -> Locale("bn")
            "fr" -> Locale.FRANCE
            "hi" -> Locale("hi")
            "in", "id" -> Locale("id", "ID")
            "fa" -> Locale("fa")
            "tr" -> Locale("tr")
            "ur" -> Locale("ur")
            else -> Locale.US
        }
    }

    /**
     * Check if user has enabled TTS mode (vs tone or silent)
     * Bug Fix #3: More reliable DataStore reading with explicit logging
     */
    fun isTTSEnabled(context: Context, mosqueId: String? = null): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val isDebug = prefs.getBoolean("DEBUG_MODE_ENABLED", false)
        val debugIds = listOf("SG009", "SA009")

        if (isDebug && mosqueId != null && debugIds.contains(mosqueId)) {
            Log.w(TAG, "⚠️ DEBUG: Forcing TTS enabled for test mosque=$mosqueId")
            return true
        }

        return try {
            val audioMode = runBlocking {
                context.prayerSettingsDataStore.data.first()[
                    stringPreferencesKey("pref_secondary_audio_mode")
                ] ?: "tts"
            }
            Log.d(TAG, "📊 DataStore read - pref_secondary_audio_mode: '$audioMode'")
            val isEnabled = audioMode == "tts"
            Log.d(TAG, "📊 TTS Enabled check result: $isEnabled (mode: $audioMode)")
            isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check TTS mode from DataStore", e)
            true
        }
    }

}
