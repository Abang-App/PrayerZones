package com.abang.prayerzones.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

data class DonationVerificationResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    @SerializedName("amount") val amount: Int? = null
)

interface DonationVerificationApi {
    @GET("verify-session")
    suspend fun verifySession(
        @Query("session_id") sessionId: String
    ): Response<DonationVerificationResponse>
}

