package com.abang.prayerzones.model

// --- START: New Data Class for Jumu'ah ---
// This class will hold the specific times for Jumu'ah prayers.

data class JumuahInfo(
    val session1: String? = null, // If null, defaults to Dhuhr time.
    val session2: String? = null  // If null, it is not displayed.
)

enum class NotificationType {
    AZAN, TTS, ANIMAL, SYSTEM
}

// Data model for a Mosque with optional 'jumuah' and 'defaultTimings'
data class Mosque(
    val id: String,
    val name: String,             // Mosque name for display
    val displayCity: String,      // Friendly for UI
    val displayCountry: String,   // Friendly for UI
    val apiCity: String,          // Strict for API
    val apiCountry: String,       // Strict for API
    val channelId: String,
    @Deprecated("Use timeZone instead")
    val notifType: NotificationType,  // New enum field
    val fajrSound: String,            // Path or name of the file
    val duhaSound: String,            // Path or name of the file
    val dhuhrSound: String,         // Path or name of the file
    val asrSound: String,
    val maghribSound: String,
    val ishaSound: String,
    /**
     * Canonical IANA time zone ID, e.g. "Europe/Paris".
     * This is what we should use for all calculations/scheduling.
     */
    val timeZone: String, // e.g., "Europe/Paris"
    val latitude: Double,         // GPS coordinate for distance calculation
    val longitude: Double,        // GPS coordinate for distance calculation
    val jumuah: JumuahInfo? = null, // Optional Jumu'ah info
    val defaultTimings: Map<String, String>? = null // Optional fallback timings
) {
    /**
     * Validates the TimeZone immediately.
     * Returns NULL if the timezone is invalid (e.g., typo "Asia/Singapor").
     * We do NOT fall back to systemDefault().
     */
    val validZoneId: java.time.ZoneId?
        get() = runCatching { java.time.ZoneId.of(timeZone) }.getOrNull()

    /**
     * Strict Local Time.
     * Throws an exception or returns null if Zone is invalid.
     * The ViewModel must handle this failure by showing an "Error Card" for this mosque.
     */
    fun getCurrentLocalTime(): java.time.ZonedDateTime {
        val zone = validZoneId
            ?: throw IllegalStateException("CRITICAL: Invalid TimeZone '$timeZone' for Mosque '$name'. Cannot calculate time.")

        return java.time.ZonedDateTime.now(zone)
    }
}



// Master Inventory: 10 Mosques with coordinates for GPS nearest calculation
internal val initialMosques = listOf(
    Mosque(
        id = "SG001",
        name = "Al-Khair Mosque",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3826,
        longitude = 103.7499,
        jumuah = JumuahInfo(session1 = null, session2 = "14:00") // jumua'ah 1 time = Dhuhr time
    ),
    Mosque(
        id = "SA001",
        name = "Masjid Al-Haram",
        displayCity = "Makkah",
        displayCountry = "Saudi Arabia",
        apiCity = "Makkah",
        apiCountry = "Saudi Arabia",
        channelId = "UCos52azQNBgW63_9uDJoPDA",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Riyadh",
        latitude = 21.4225,
        longitude = 39.8262,
        jumuah = JumuahInfo(session1 = "12:30")
    ),
    Mosque(
        id = "SA002",
        name = "Masjid Al-Nabawi",
        displayCity = "Madinah",
        displayCountry = "Saudi Arabia",
        apiCity = "Madinah",
        apiCountry = "Saudi Arabia",
        channelId = "UCROKYPep-UuODNwyipe6JMw",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Riyadh",
        latitude = 24.468333,
        longitude = 39.610833,
        jumuah = JumuahInfo(session1 = "12:35")
    ),
    Mosque(
        id = "FR001",
        name = "Grande Mosquée de Paris",
        displayCity = "Paris",
        displayCountry = "France",
        apiCity = "Paris",
        apiCountry = "France",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Europe/Paris",
        latitude = 48.8427,
        longitude = 2.3554,
        jumuah = JumuahInfo(session1 = "13:30")
    ),
	Mosque(
        id = "FR002",
        name = "Mosquée Al Ikhlas",
        displayCity = "Fontenay Sous Bois",
        displayCountry = "France",
        apiCity = "Fontenay-sous-Bois",
        apiCountry = "France",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Europe/Paris",
		latitude = 48.8566,
        longitude = 2.3522,
        jumuah = JumuahInfo(session1 = "13:30")
    ),
    Mosque(
        id = "FR003",
        name = "Mosquée de Bouzignac",
        displayCity = "Tours",
        displayCountry = "France",
        apiCity = "Tours",
        apiCountry = "France",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Europe/Paris",
        latitude = 47.3757,
        longitude = 0.7108,
        jumuah = JumuahInfo(session1 = "13:00")
    ),
    Mosque(
        id = "SG002",
        name = "Masjid Al-Muttaqin",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3703,
        longitude = 103.8458,
        jumuah = JumuahInfo(session1 = "13:00")
    ),
    Mosque(
        id = "SG003",
        name = "Masjid An-Nur",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.4429,
        longitude = 103.7744,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    Mosque(
        id = "SG004",
        name = "Masjid Maarof",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3503,
        longitude = 103.6980,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    Mosque(
        id = "SG005",
        name = "Masjid Ar-Raudhah",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3456,
        longitude = 103.7570,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    Mosque(
        id = "SG006",
        name = "Masjid Al-Iman",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3782,
        longitude = 103.7727,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    // BN001 - The National Mosque (Iconic)
    Mosque(
        id = "BN001",
        name = "Jame' 'Asr Hassanil Bolkiah",
        displayCity = "Bandar Seri Begawan",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.8979,
        longitude = 114.9219,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN002 - The Sultan Omar Ali Saifuddien Mosque (Iconic)
    Mosque(
        id = "BN002",
        name = "Masjid Omar Ali Saifuddien",
        displayCity = "Bandar Seri Begawan",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.8895,
        longitude = 114.9392,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN003 - Major Mosque in the Capital District
    Mosque(
        id = "BN003",
        name = "Masjid Ash-Shaliheen",
        displayCity = "Bandar Seri Begawan",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.9181,
        longitude = 114.9359,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN004 - Large Residential Area Mosque
    Mosque(
        id = "BN004",
        name = "Masjid Mohamed Bolkiah",
        displayCity = "Kampong Serusop",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.9450,
        longitude = 114.9447,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN005 - University/Knowledge Hub Mosque
    Mosque(
        id = "BN005",
        name = "Masjid Universiti Brunei Darussalam",
        displayCity = "Gadong",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.9773,
        longitude = 114.8958,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    Mosque(
        id = "BN006",
        name = "Masjid Kampong Pandan",
        displayCity = "Kuala Belait",
        displayCountry = "Brunei",
        apiCity = "Belait",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.5862,
        longitude = 114.2324,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN007 - Main Mosque for Belait District
    Mosque(
        id = "BN007",
        name = "Masjid Mohammad Jamalul Alam",
        displayCity = "Kuala Belait",
        displayCountry = "Brunei",
        apiCity = "Belait",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.5852,
        longitude = 114.1903,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN008 - Important Oil-Town Mosque
    Mosque(
        id = "BN008",
        name = "Masjid Setia Ali",
        displayCity = "Pekan Muara",
        displayCountry = "Brunei",
        apiCity = "Brunei-Muara",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 5.0291,
        longitude = 115.0613,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN009 - Main Mosque for Tutong District
    Mosque(
        id = "BN009",
        name = "Masjid Hassanal Bolkiah Tutong",
        displayCity = "Pekan Tutong",
        displayCountry = "Brunei",
        apiCity = "Tutong",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.8065,
        longitude = 114.6620,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
// BN010 - Main Mosque for Temburong District (Enclave)
    Mosque(
        id = "BN010",
        name = "Masjid Utama Mohammad Salleh",
        displayCity = "Pekan Bangar",
        displayCountry = "Brunei",
        apiCity = "Temburong",
        apiCountry = "Brunei",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Brunei",
        latitude = 4.7077,
        longitude = 115.0707,
        jumuah = JumuahInfo(session1 = "13:10")
    ),
    Mosque(
        id = "ID001",
        name = "Masjid Muhammad Cheng Hoo",
        displayCity = "Batam",
        displayCountry = "Indonesia",
        apiCity = "Batam",
        apiCountry = "Indonesia",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Jakarta", // Western Indonesia Time (WIB)
        latitude = 1.1558,
        longitude = 104.0322,
        jumuah = JumuahInfo(session1 = "12:20")
    ),
    Mosque(
        id = "ID002",
        name = "Batam Center Grand Mosque",
        displayCity = "Batam",
        displayCountry = "Indonesia",
        apiCity = "Batam",
        apiCountry = "Indonesia",
        channelId= "",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Jakarta",
        latitude = 1.1264,
        longitude = 104.0531,
        jumuah = JumuahInfo(session1 = "12:18")
    ),
    Mosque(
        id = "SG009",
        name = "Al-Khair Mosque 1",
        displayCity = "Singapore",
        displayCountry = "Singapore",
        apiCity = "Singapore",
        apiCountry = "Singapore",
        channelId = "",
        notifType = NotificationType.AZAN,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Singapore",
        latitude = 1.3826,
        longitude = 103.7499,
        jumuah = JumuahInfo(session1 = null, session2 = "14:00"),
        defaultTimings = null // Populated dynamically
    ),
    Mosque(
        id = "SA009",
        name = "Masjid Al-Nabawi 1",
        displayCity = "Madinah",
        displayCountry = "Saudi Arabia",
        apiCity = "Madinah",
        apiCountry = "Saudi Arabia",
        channelId = "UCROKYPep-UuODNwyipe6JMw",
        notifType = NotificationType.TTS,
        fajrSound = "fajr.mp3",
        duhaSound = "duha.mp3",
        dhuhrSound = "dhuhr.mp3",
        asrSound = "asr.mp3",
        maghribSound = "maghrib.mp3",
        ishaSound = "isha.mp3",
        timeZone = "Asia/Riyadh",
        latitude = 24.468333,
        longitude = 39.610833,
        jumuah = JumuahInfo(session1 = "12:35"),
        defaultTimings = null // Populated dynamically
    )
)