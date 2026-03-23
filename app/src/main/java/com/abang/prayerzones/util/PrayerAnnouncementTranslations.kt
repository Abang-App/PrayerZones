package com.abang.prayerzones.util

/**
 * Strict, pre-translated "Prayer starts now" announcements per language.
 *
 * This avoids relying on the TTS engine to "translate" text (it only changes voice/locale).
 */
object PrayerAnnouncementTranslations {

    // Maps: languageKey -> (prayerKey -> translated sentence)
    // languageKey must be one of: en, ar, bn, fr, hi, id, tr, ur
    val map: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "Fajr" to "Fajr starts now",
            "Duha" to "Duha starts now",
            "Dhuhr" to "Dhuhr starts now",
            "Asr" to "Asr starts now",
            "Maghrib" to "Maghrib starts now",
            "Isha" to "Isha starts now"
        ),
        "fr" to mapOf(
            "Fajr" to "Fajr commence maintenant",
            "Duha" to "Duha commence maintenant",
            "Dhuhr" to "Dhouhr commence maintenant",
            "Asr" to "Asr commence maintenant",
            "Maghrib" to "Maghrib commence maintenant",
            "Isha" to "Icha commence maintenant"
        ),
        "id" to mapOf(
            "Fajr" to "Waktu Subuh dimulai sekarang",
            "Duha" to "Waktu Dhuha dimulai sekarang",
            "Dhuhr" to "Waktu Dzuhur dimulai sekarang",
            "Asr" to "Waktu Asar dimulai sekarang",
            "Maghrib" to "Waktu Maghrib dimulai sekarang",
            "Isha" to "Waktu Isya dimulai sekarang"
        ),
        "ar" to mapOf(
            "Fajr" to "حان الآن وقت صلاة الفجر",
            "Duha" to "حان الآن وقت صلاة الضحى",
            "Dhuhr" to "حان الآن وقت صلاة الظهر",
            "Asr" to "حان الآن وقت صلاة العصر",
            "Maghrib" to "حان الآن وقت صلاة المغرب",
            "Isha" to "حان الآن وقت صلاة العشاء"
        ),
        "bn" to mapOf(
            "Fajr" to "এখন ফজরের সময় শুরু হয়েছে",
            "Duha" to "এখন দুহার সময় শুরু হয়েছে",
            "Dhuhr" to "এখন যোহরের সময় শুরু হয়েছে",
            "Asr" to "এখন আসরের সময় শুরু হয়েছে",
            "Maghrib" to "এখন মাগরিবের সময় শুরু হয়েছে",
            "Isha" to "এখন ইশার সময় শুরু হয়েছে"
        ),
        "hi" to mapOf(
            "Fajr" to "अब फ़ज्र का समय शुरू हो गया है",
            "Duha" to "अब दुहा का समय शुरू हो गया है",
            "Dhuhr" to "अब ज़ुहर का समय शुरू हो गया है",
            "Asr" to "अब असर का समय शुरू हो गया है",
            "Maghrib" to "अब मगरिब का समय शुरू हो गया है",
            "Isha" to "अब ईशा का समय शुरू हो गया है"
        ),
        "tr" to mapOf(
            "Fajr" to "Sabah namazı vakti şimdi başladı",
            "Duha" to "Duha vakti şimdi başladı",
            "Dhuhr" to "Öğle namazı vakti şimdi başladı",
            "Asr" to "İkindi namazı vakti şimdi başladı",
            "Maghrib" to "Akşam namazı vakti şimdi başladı",
            "Isha" to "Yatsı namazı vakti şimdi başladı"
        ),
        "ur" to mapOf(
            "Fajr" to "اب فجر کی نماز کا وقت شروع ہو گیا ہے",
            "Duha" to "اب چاشت کی نماز کا وقت شروع ہو گیا ہے",
            "Dhuhr" to "اب ظہر کی نماز کا وقت شروع ہو گیا ہے",
            "Asr" to "اب عصر کی نماز کا وقت شروع ہو گیا ہے",
            "Maghrib" to "اب مغرب کی نماز کا وقت شروع ہو گیا ہے",
            "Isha" to "اب عشاء کی نماز کا وقت شروع ہو گیا ہے"
        )
    )

    fun get(languageKey: String, prayerKey: String): String? {
        return map[languageKey]?.get(prayerKey)
    }
}
