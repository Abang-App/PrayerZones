package com.abang.prayerzones.model

data class PrayerResponse(
    val code: Int,
    val status: String,
    val data: PrayerData
)

data class PrayerData(
    val timings: Map<String, String>,
    val date: PrayerDate,
    val meta: PrayerMeta
)

data class PrayerDate(
    val readable: String,
    val timestamp: String,
    val gregorian: GregorianDate,
    val hijri: HijriDate
)

data class PrayerMeta(val timezone: String, val method: CalculationMethod)
data class CalculationMethod(val id: Int, val name: String)
