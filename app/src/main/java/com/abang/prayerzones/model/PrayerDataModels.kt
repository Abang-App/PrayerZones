package com.abang.prayerzones.model // Or your preferred package

import com.google.gson.annotations.SerializedName

// Top-level response object
data class PrayerApiResponse(
    @SerializedName("code")
    val code: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: PrayerFullData
)

// The main "data" object
data class PrayerFullData(
    @SerializedName("timings")
    val timings: PrayerTimings,

    @SerializedName("date")
    val dateInfo: DateInfo,

    @SerializedName("meta")
    val meta: MetaInfo
)

// Timings object with all prayer times
data class PrayerTimings(
    @SerializedName("Fajr")
    val fajr: String,

    @SerializedName("Sunrise")
    val sunrise: String,

    @SerializedName("Dhuhr")
    val dhuhr: String,

    @SerializedName("Asr")
    val asr: String,

    @SerializedName("Sunset")
    val sunset: String,

    @SerializedName("Maghrib")
    val maghrib: String,

    @SerializedName("Isha")
    val isha: String,

    @SerializedName("Imsak")
    val imsak: String,

    @SerializedName("Midnight")
    val midnight: String,

    @SerializedName("Firstthird")
    val firstThird: String? = null, // Mark as nullable if it might be missing or not always needed

    @SerializedName("Lastthird")
    val lastThird: String? = null // Mark as nullable
)

// Date information object
data class DateInfo(
    @SerializedName("readable")
    val readable: String,

    @SerializedName("timestamp")
    val timestamp: String, // Or Long if you prefer to parse it immediately

    @SerializedName("hijri")
    val hijri: HijriDate,

    @SerializedName("gregorian")
    val gregorian: GregorianDate
)

// Hijri date details
data class HijriDate(
    @SerializedName("date")
    val date: String,

    @SerializedName("format")
    val format: String,

    @SerializedName("day")
    val day: String,

    @SerializedName("weekday")
    val weekday: Weekday,

    @SerializedName("month")
    val month: MonthInfo,

    @SerializedName("year")
    val year: String,

    @SerializedName("designation")
    val designation: Designation,

    @SerializedName("holidays")
    val holidays: List<String> = emptyList(), // Default to empty list if missing

    @SerializedName("adjustedHolidays")
    val adjustedHolidays: List<String> = emptyList(),

    @SerializedName("method")
    val method: String? = null // Added as it was present in your full JSON
)

// Gregorian date details
data class GregorianDate(
    @SerializedName("date")
    val date: String,

    @SerializedName("format")
    val format: String,

    @SerializedName("day")
    val day: String,

    @SerializedName("weekday")
    val weekday: WeekdaySimple, // Simpler weekday for Gregorian

    @SerializedName("month")
    val month: MonthSimple, // Simpler month for Gregorian

    @SerializedName("year")
    val year: String,

    @SerializedName("designation")
    val designation: Designation,

    @SerializedName("lunarSighting")
    val lunarSighting: Boolean? = null // Added as it was present
)

// Common Weekday object
data class Weekday(
    @SerializedName("en")
    val en: String,

    @SerializedName("ar")
    val ar: String? = null // Arabic might be optional sometimes
)

// Simpler Weekday for Gregorian (only "en")
data class WeekdaySimple(
    @SerializedName("en")
    val en: String
)

// Common Month object
data class MonthInfo(
    @SerializedName("number")
    val number: Int,

    @SerializedName("en")
    val en: String,

    @SerializedName("ar")
    val ar: String? = null,

    @SerializedName("days")
    val days: Int? = null // Not present in Gregorian month from your JSON
)

// Simpler Month for Gregorian
data class MonthSimple(
    @SerializedName("number")
    val number: Int,

    @SerializedName("en")
    val en: String
)

// Designation object
data class Designation(
    @SerializedName("abbreviated")
    val abbreviated: String,

    @SerializedName("expanded")
    val expanded: String
)

// Meta information object
data class MetaInfo(
    @SerializedName("latitude")
    val latitude: Double,

    @SerializedName("longitude")
    val longitude: Double,

    @SerializedName("timezone")
    val timezone: String,

    @SerializedName("method")
    val method: MethodDetails,

    @SerializedName("latitudeAdjustmentMethod")
    val latitudeAdjustmentMethod: String,

    @SerializedName("midnightMode")
    val midnightMode: String,

    @SerializedName("school")
    val school: String,

    @SerializedName("offset")
    val offset: Map<String, Int> // Represents key-value pairs like "Imsak": 0
)

// Method details within Meta
data class MethodDetails(
    @SerializedName("id")
    val id: Int,

    @SerializedName("name")
    val name: String,

    @SerializedName("params")
    val params: MethodParams,

    @SerializedName("location")
    val location: MethodLocation? = null // Added as it was present
)

// Parameters for the method
data class MethodParams(
    @SerializedName("Fajr")
    val fajrAngle: Any, // Can be Double (19.5) or String ("90 min"), handle with care

    @SerializedName("Isha")
    val ishaValue: Any // Can be String ("90 min") or Angle
)

data class MethodLocation(
    @SerializedName("latitude")
    val latitude: Double,
    @SerializedName("longitude")
    val longitude: Double
)
