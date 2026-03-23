package com.abang.prayerzones.api

import android.util.Log
import com.abang.prayerzones.model.PrayerResponse
import retrofit2.Retrofit // Import Retrofit
import retrofit2.converter.gson.GsonConverterFactory // Import GsonConverterFactory (based on your dependencies)
import retrofit2.http.GET
import retrofit2.http.Query

interface PrayerApi {
    // Example: https://api.aladhan.com/v1/timingsByCity?city=Singapore&country=Singapore
    @GET("timingsByCity")
    suspend fun getPrayerTimes(
        @Query("city") city: String,
        @Query("country") country: String
    ): PrayerResponse


    }


