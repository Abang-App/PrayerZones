package com.abang.prayerzones.api

import com.abang.prayerzones.model.PrayerResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface PrayerApiService {

    // Replace this with your actual API endpoint and parameters
    @GET("your/api/endpoint")
    suspend fun getPrayerTimes(
        @Query("city") city: String,
        @Query("country") country: String
    ): Response<PrayerResponse>
}
