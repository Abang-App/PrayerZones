package com.abang.prayerzones.util

import androidx.core.text.BidiFormatter

/**
 * Central registry for TTS announcement translations.
 *
 * Keeps locale-specific sentence templates + mosque term normalization in one place.
 */
object TranslationRegistry {

    data class TranslationTemplate(
        val timeForPrayer: String,
        val connectorAt: String,
        val localMosqueTerm: String,
        val isRTL: Boolean = false
    )

    // Use language tags used by DataStore (we normalize to these keys).
    private val registry: Map<String, TranslationTemplate> = mapOf(
        "en" to TranslationTemplate(
            timeForPrayer = "It is time for",
            connectorAt = "at",
            localMosqueTerm = "Mosque"
        ),
        "ar" to TranslationTemplate(
            timeForPrayer = "حان وقت صلاة",
            connectorAt = "في",
            localMosqueTerm = "مسجد",
            isRTL = true
        ),
        "fr" to TranslationTemplate(
            timeForPrayer = "C'est l'heure de la prière de",
            connectorAt = "à la",
            localMosqueTerm = "Mosquée"
        ),
        "bn" to TranslationTemplate(
            timeForPrayer = "এখন",
            connectorAt = "এ",
            localMosqueTerm = "মসজিদ"
        ),
        "hi" to TranslationTemplate(
            timeForPrayer = "अब",
            connectorAt = "में",
            localMosqueTerm = "मस्जिद"
        ),
        "tr" to TranslationTemplate(
            timeForPrayer = "Namaz vakti",
            connectorAt = "için",
            localMosqueTerm = "Camii"
        ),
        "id" to TranslationTemplate(
            timeForPrayer = "Waktunya sholat",
            connectorAt = "di",
            localMosqueTerm = "Masjid"
        ),
        "ur" to TranslationTemplate(
            timeForPrayer = "اب",
            connectorAt = "میں",
            localMosqueTerm = "مسجد",
            isRTL = true
        )
    )

    /**
     * Normalize incoming DataStore language code to a registry key.
     *
     * Examples:
     * - en_US / en_GB -> en
     * - in -> id
     */
    fun normalizeLanguageKey(languageCode: String): String {
        val raw = languageCode.trim().lowercase()
        return when {
            raw.startsWith("en") -> "en"
            raw.startsWith("ar") -> "ar"
            raw.startsWith("bn") -> "bn"
            raw.startsWith("fr") -> "fr"
            raw.startsWith("hi") -> "hi"
            raw.startsWith("tr") -> "tr"
            raw == "in" -> "id"
            raw.startsWith("id") -> "id"
            raw.startsWith("ur") -> "ur"
            else -> "en"
        }
    }

    /**
     * Map of prayer name translations (just the name, not full sentences).
     */
    private val prayerNameTranslations: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "Fajr" to "Fajr",
            "Duha" to "Duha",
            "Dhuhr" to "Dhuhr",
            "Asr" to "Asr",
            "Maghrib" to "Maghrib",
            "Isha" to "Isha"
        ),
        "fr" to mapOf(
            "Fajr" to "Fajr",
            "Duha" to "Duha",
            "Dhuhr" to "Dhouhr",
            "Asr" to "Asr",
            "Maghrib" to "Maghrib",
            "Isha" to "Icha"
        ),
        "id" to mapOf(
            "Fajr" to "Subuh",
            "Duha" to "Dhuha",
            "Dhuhr" to "Dzuhur",
            "Asr" to "Asar",
            "Maghrib" to "Maghrib",
            "Isha" to "Isya"
        ),
        "ar" to mapOf(
            "Fajr" to "الفجر",
            "Duha" to "الضحى",
            "Dhuhr" to "الظهر",
            "Asr" to "العصر",
            "Maghrib" to "المغرب",
            "Isha" to "العشاء"
        ),
        "bn" to mapOf(
            "Fajr" to "ফজর",
            "Duha" to "দুহা",
            "Dhuhr" to "যোহর",
            "Asr" to "আসর",
            "Maghrib" to "মাগরিব",
            "Isha" to "ইশা"
        ),
        "hi" to mapOf(
            "Fajr" to "फ़ज्र",
            "Duha" to "दुहा",
            "Dhuhr" to "ज़ुहर",
            "Asr" to "असर",
            "Maghrib" to "मगरिब",
            "Isha" to "ईशा"
        ),
        "tr" to mapOf(
            "Fajr" to "Sabah",
            "Duha" to "Duha",
            "Dhuhr" to "Öğle",
            "Asr" to "İkindi",
            "Maghrib" to "Akşam",
            "Isha" to "Yatsı"
        ),
        "ur" to mapOf(
            "Fajr" to "فجر",
            "Duha" to "چاشت",
            "Dhuhr" to "ظہر",
            "Asr" to "عصر",
            "Maghrib" to "مغرب",
            "Isha" to "عشاء"
        )
    )

    /**
     * Translate a prayer name to the target language.
     * Returns the original prayer name if no translation is found.
     */
    fun translatePrayerName(prayerKey: String, languageKey: String): String {
        val normalizedKey = normalizeLanguageKey(languageKey)
        return prayerNameTranslations[normalizedKey]?.get(prayerKey) ?: prayerKey
    }

    /**
     * Strict translated form: "Prayer starts now" in target language.
     * Falls back to a simple English string if missing.
     *
     * @deprecated Use translatePrayerName and getAnnouncement instead to avoid duplication.
     */
    @Deprecated("Use translatePrayerName and getAnnouncement for cleaner sentence structure")
    fun getStrictPrayerStartsNow(prayerKey: String, languageKey: String): String {
        val normalizedKey = normalizeLanguageKey(languageKey)
        return PrayerAnnouncementTranslations.get(normalizedKey, prayerKey)
            ?: PrayerAnnouncementTranslations.get("en", prayerKey)
            ?: "$prayerKey starts now"
    }

    fun getTemplate(languageKey: String): TranslationTemplate {
        return registry[languageKey] ?: registry.getValue("en")
    }

    /**
     * Translate mosque name using phonetic registry.
     *
     * @param mosqueId The unique mosque identifier (for specific overrides)
     * @param mosqueName The original mosque name
     * @param languageKey The target language code
     * @return The phonetically-optimized mosque name
     */
    fun translateMosqueName(mosqueId: String, mosqueName: String, languageKey: String): String {
        val normalizedKey = normalizeLanguageKey(languageKey)
        return MosquePhoneticRegistry.getPhoneticName(mosqueId, mosqueName, normalizedKey)
    }

    /**
     * Build the final announcement.
     *
     * Structure: [TimeForPrayer] + [LocalizedPrayerName] + [Connector] + [PhoneticMosqueName]
     *
     * Example outputs:
     * - English: "It is time for Fajr at Masjid Al-Nabawi"
     * - French: "C'est l'heure de la prière de Fajr à la Mosquée Al-Khaïr"
     * - Indonesian: "Waktunya sholat Subuh di Masjid Al-Ikhlas"
     *
     * @param prayerName The raw prayer name (e.g., "Fajr")
     * @param mosqueName The raw mosque name
     * @param mosqueId The mosque identifier (for phonetic overrides)
     * @param languageKey The target language code
     */
    fun getAnnouncement(
        prayerName: String,
        mosqueName: String,
        languageKey: String,
        mosqueId: String = ""
    ): String {
        val normalizedKey = normalizeLanguageKey(languageKey)
        val template = getTemplate(normalizedKey)

        // Translate prayer name to target language
        val localizedPrayerName = translatePrayerName(prayerName, normalizedKey)

        // Get phonetically-optimized mosque name
        val phoneticMosqueName = if (mosqueId.isNotEmpty()) {
            translateMosqueName(mosqueId, mosqueName, normalizedKey)
        } else {
            // Fallback: generic phonetic cleaning
            MosquePhoneticRegistry.getPhoneticName("", mosqueName, normalizedKey)
        }

        val finalMosqueName = if (template.isRTL) {
            // Prevent Latin words from breaking RTL flow.
            BidiFormatter.getInstance(true).unicodeWrap(phoneticMosqueName)
        } else {
            phoneticMosqueName
        }

        // Build clean sentence structure
        return "${template.timeForPrayer} $localizedPrayerName ${template.connectorAt} $finalMosqueName"
    }

    /**
     * Overload for backward compatibility (without mosqueId).
     */
    fun getAnnouncement(prayerName: String, mosqueName: String, languageKey: String): String {
        return getAnnouncement(prayerName, mosqueName, languageKey, "")
    }
}
