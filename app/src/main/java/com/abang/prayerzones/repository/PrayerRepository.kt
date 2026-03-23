package com.abang.prayerzones.repository

import com.abang.prayerzones.api.PrayerApi
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.PrayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrayerRepository @Inject constructor(
    private val api: PrayerApi
) {
    // Thread-safe in-memory cache using Mutex
    private val memoryCache = mutableMapOf<String, PrayerResponse>()
    private val cacheMutex = Mutex()

    /**
     * Get cached prayer times from memory (instant, 0ms)
     * Returns null if not cached yet
     */
    suspend fun getCachedPrayerTimes(mosqueId: String): PrayerResponse? {
        return cacheMutex.withLock {
            memoryCache[mosqueId]
        }
    }

    /**
     * Fetch prayer times from network and update memory cache.
     */
    suspend fun getPrayerTimes(mosque: Mosque): PrayerResponse {
        return withContext(Dispatchers.IO) {
            val response = api.getPrayerTimes(mosque.apiCity, mosque.apiCountry)

            cacheMutex.withLock {
                memoryCache[mosque.id] = response
            }

            response
        }
    }

    /**
     * Best-effort helper: fetch prayer times and also return the API-provided IANA timezone (meta.timezone).
     * Callers can persist this into their Mosque object as `mosque.copy(timeZone = metaTimezone)`.
     */
    @Suppress("unused")
    suspend fun getPrayerTimesWithTimezone(mosque: Mosque): Pair<PrayerResponse, String> {
        val response = getPrayerTimes(mosque)
        return response to response.data.meta.timezone
    }

    /**
     * Manually update the memory cache (useful when loading from disk cache)
     */
    @Suppress("unused")
    suspend fun updateMemoryCache(mosqueId: String, response: PrayerResponse) {
        cacheMutex.withLock {
            memoryCache[mosqueId] = response
        }
    }
}
