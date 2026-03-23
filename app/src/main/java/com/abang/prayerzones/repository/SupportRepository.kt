package com.abang.prayerzones.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.abang.prayerzones.api.DonationVerificationApi
import com.abang.prayerzones.model.Supporter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupportRepository @Inject constructor(
    private val donationVerificationApi: DonationVerificationApi,
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "SupportRepository"
        const val PREFS_NAME = "prayer_zones_prefs"
        const val KEY_SUPPORTERS = "supporters_list"
    }

    data class VerifiedDonation(
        val customerName: String,
        val amount: Int
    )

    private val processedSessionIds = mutableSetOf<String>()
    private val gson = Gson()

    private val _supporters = MutableStateFlow<List<Supporter>>(emptyList())
    val supporters: StateFlow<List<Supporter>> = _supporters.asStateFlow()

    init {
        loadSupporters()
    }

    private fun loadSupporters() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SUPPORTERS, null)
        
        if (json != null) {
            try {
                val type = object : TypeToken<List<Supporter>>() {}.type
                val savedList: List<Supporter> = gson.fromJson(json, type)
                _supporters.value = savedList
            } catch (e: Exception) {
                Log.e(TAG, "Error loading supporters", e)
                initializeDummyData()
            }
        } else {
            initializeDummyData()
        }
    }

    private fun initializeDummyData() {
        val dummyData = listOf(
            Supporter("Amir", 3, "15-03-26"),
            Supporter("Sarah", 10, "14-03-26"),
            Supporter("John", 5, "14-03-26"),
            Supporter("Emily", 25, "13-03-26"),
            Supporter("Michael", 3, "12-03-26"),
            Supporter("Jessica", 5, "12-03-26")
        )
        _supporters.value = dummyData
        saveSupporters(dummyData)
    }

    private fun saveSupporters(list: List<Supporter>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_SUPPORTERS, json).apply()
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date())
    }

    fun addSupporter(name: String, amount: Int) {
        val cleanedName = name.trim().ifEmpty { "Donor" }
        if (amount <= 0) return
        addOrUpdateSupporter(Supporter(cleanedName, amount, getCurrentDateString()))
    }

    suspend fun verifyDonationSession(
        sessionId: String,
        fallbackName: String? = null,
        fallbackAmount: Int? = null
    ): VerifiedDonation? {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty()) return null

        val normalizedFallbackName = fallbackName?.trim().takeUnless { it.isNullOrEmpty() } ?: "anonymous"
        val normalizedFallbackAmount = fallbackAmount ?: 0

        fun fallbackDonation(reason: String, throwable: Throwable? = null): VerifiedDonation? {
            if (!normalizedFallbackName.equals("anonymous", ignoreCase = true) && normalizedFallbackAmount > 0) {
                val fallback = Supporter(normalizedFallbackName, normalizedFallbackAmount, getCurrentDateString())
                addOrUpdateSupporter(fallback)
                Log.w(TAG, "Using URI fallback donor due to $reason: name=${fallback.name}, amount=${fallback.amount}", throwable)
                return VerifiedDonation(customerName = fallback.name, amount = fallback.amount)
            }
            Log.w(TAG, "Fallback donor ignored due to missing/anonymous data ($reason): name=$normalizedFallbackName amount=$normalizedFallbackAmount", throwable)
            return null
        }

        if (processedSessionIds.contains(normalizedSessionId)) {
            Log.d(TAG, "Skipping duplicate verification for sessionId=$normalizedSessionId")
            return null
        }

        return try {
            val response = donationVerificationApi.verifySession(normalizedSessionId)
            if (!response.isSuccessful) {
                Log.e(TAG, "Worker HTTP Error: Code=${response.code()}, Body=${response.errorBody()?.string()}")
                fallbackDonation(reason = "HTTP error ${response.code()}")
            } else {
                val body = response.body()
                if (body == null) {
                    Log.e(TAG, "Worker returned null body")
                    fallbackDonation(reason = "null response body")
                } else if (!body.status.equals("success", ignoreCase = true)) {
                    Log.w(TAG, "Verification failed for sessionId=$normalizedSessionId status=${body.status}")
                    fallbackDonation(reason = "non-success status=${body.status}")
                } else {
                    val serverCustomerName = body.customerName?.trim()
                    
                    // Logic: Trust the server name, unless it's missing/anonymous and we have a better specific name locally.
                    val customerName = if (serverCustomerName.isNullOrEmpty() || serverCustomerName.equals("anonymous", ignoreCase = true)) {
                        if (!normalizedFallbackName.equals("anonymous", ignoreCase = true)) {
                            normalizedFallbackName
                        } else {
                            "anonymous"
                        }
                    } else {
                        serverCustomerName
                    }

                    val amount = body.amount ?: 3

                    processedSessionIds += normalizedSessionId
                    addOrUpdateSupporter(Supporter(customerName, amount, getCurrentDateString()))
                    VerifiedDonation(customerName = customerName, amount = amount)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying donation sessionId=$normalizedSessionId", e)
            fallbackDonation(reason = "exception", throwable = e)
        }
    }

    private fun addOrUpdateSupporter(supporter: Supporter) {
        _supporters.update { current ->
            val filtered = current.filterNot { it.name == supporter.name && it.amount == supporter.amount }
            // Insert at top (index 0)
            val newList = listOf(supporter) + filtered
            saveSupporters(newList)
            newList
        }
    }
}
