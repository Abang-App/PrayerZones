package com.abang.prayerzones.util

/**
 * Registry for mosque-specific phonetic overrides per language.
 *
 * Purpose: Improve TTS pronunciation for specific mosques in specific languages.
 *
 * Key format: Pair(mosqueId, languageCode)
 * Value: The phonetically-optimized mosque name for that language
 */
object MosquePhoneticRegistry {

    /**
     * Map of mosque-specific phonetic overrides.
     * Only add entries when the default cleaning is insufficient.
     */
    private val mosquePhoneticOverrides: Map<Pair<String, String>, String> = mapOf(
        // French phonetic improvements
        Pair("SA001", "fr") to "Mosquée Al-Harâm",
        Pair("SG001", "fr") to "Mosquée Al-Khaïr",

        // Add more overrides as needed
        // Example:
        // Pair("EG001", "fr") to "Mosquée Al-Azhar",
    )

    /**
     * Get the phonetically-optimized mosque name for a specific language.
     *
     * @param mosqueId The unique mosque identifier
     * @param mosqueName The original mosque name
     * @param languageCode The target language code (e.g., "fr", "ar", "en")
     * @return The phonetically-optimized name, or the original if no override exists
     */
    fun getPhoneticName(mosqueId: String, mosqueName: String, languageCode: String): String {
        // Check for specific override
        val override = mosquePhoneticOverrides[Pair(mosqueId, languageCode)]
        if (override != null) return override

        // Apply generic phonetic cleaning based on language
        return applyGenericPhoneticCleaning(mosqueName, languageCode)
    }

    /**
     * Apply generic phonetic improvements based on language patterns.
     */
    private fun applyGenericPhoneticCleaning(mosqueName: String, languageCode: String): String {
        return when (languageCode) {
            "en" -> mosqueName
                .replace("Masjid Al-Haram", "The Great Mosque of Mecca", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "The Prophet’s Mosque", ignoreCase = true)
                .replace("Al-Masjid", "The Mosque", ignoreCase = true)
                .replace("Masjid", "Mosque", ignoreCase = true)

            "fr" -> mosqueName
                .replace("Masjid Al-Haram", "La Grande Mosquée de la Mecque", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "La Mosquée du Prophète", ignoreCase = true)
                .replace("Al-Masjid", "La Mosquée ", ignoreCase = true)
                .replace("Masjid", "Mosquée", ignoreCase = true)
                .replace("kh", "khaï", ignoreCase = true)

            "ar" -> mosqueName
                // 1. Specific iconic overrides first
                .replace("Masjid Al-Haram", "المسجد الحرام", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "المسجد النبوي", ignoreCase = true)
                .replace("Mosquée Al Ikhlas", "مسجد الإخلاص", ignoreCase = true)
                .replace("Al-Khair Mosque", "مسجد الخير", ignoreCase = true)
                // 2. Generic prefix replacements
                .replace("Al-Masjid", "المسجد", ignoreCase = true)
                .replace("Masjid", "مسجد", ignoreCase = true)
                .replace("Al-", "ال", ignoreCase = true)
                .replace("Al ", "ال", ignoreCase = true)

            "id", "in" -> mosqueName
                .replace("Masjid Al-Haram", "Masjidil Haram", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "Masjid Nabawi", ignoreCase = true)
                .replace("Masjid Al", "Masjidil", ignoreCase = true)
                .replace("Masjid", "Masjid", ignoreCase = true)

            "ur" -> mosqueName
                .replace("Masjid Al-Haram", "مسجد الحرام", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "مسجد نبوی", ignoreCase = true)
                .replace("Mosque", "مسجد", ignoreCase = true)
                .replace("Masjid", "مسجد", ignoreCase = true)

            "hi" -> mosqueName
                .replace("Masjid Al-Haram", "मस्जिद अल-हराम", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "मस्जिद-ए-नबवी", ignoreCase = true)
                .replace("Mosque", "मस्जिद", ignoreCase = true)
                .replace("Masjid", "मस्जिद", ignoreCase = true)

            "bn" -> mosqueName
                .replace("Masjid Al-Haram", "মসজিদুল হারাম", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "মসজিদে নববী", ignoreCase = true)
                .replace("Mosque", "মসজিদ", ignoreCase = true)
                .replace("Masjid", "মসজিদ", ignoreCase = true)

            "tr" -> mosqueName
                .replace("Masjid Al-Haram", "Mescid‑i Haram", ignoreCase = true)
                .replace("Masjid Al-Nabawi", "Mescid‑i Nebevi", ignoreCase = true)
                .replace("Mosque", "Camii", ignoreCase = true)
                .replace("Masjid", "Camii", ignoreCase = true)

            else -> mosqueName
        }
    }
}
