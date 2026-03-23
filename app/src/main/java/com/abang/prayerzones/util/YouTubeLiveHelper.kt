package com.abang.prayerzones.util

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lazy-refresh YouTube live URL resolver.
 *
 * Returns String? (nullable):
 *   non-null → URL is reachable, safe to launch
 *   null     → stream is confirmed offline (Firestore URL also dead) → show "Stream offline" UI
 */
object YouTubeLiveHelper {

    private const val TAG        = "YouTubeLiveHelper"
    private const val PREFS_NAME = "yt_live_cache"
    private const val REACHABILITY_TIMEOUT_MS = 3_000

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────

    /**
     * Cache-first, error-driven URL resolver.
     *
     * Returns:
     *   String  → reachable URL, ready to launch in YouTube
     *   null    → stream confirmed offline (cached URL dead + Firestore URL dead)
     *             Caller must show "Stream offline" message, do NOT launch Intent.
     */
    suspend fun getCachedOrFetch(context: Context, channelId: String): String? =
        withContext(Dispatchers.IO) {
            if (channelId.isBlank()) return@withContext null

            val prefs     = prefs(context)
            val cachedUrl = prefs.getString(urlKey(channelId), null)

            if (!cachedUrl.isNullOrBlank()) {
                if (isUrlReachable(cachedUrl)) {
                    Log.d(TAG, "Cache HIT + reachable for $channelId -> $cachedUrl")
                    return@withContext cachedUrl
                }
                // Cached URL is dead — invalidate and try Firestore
                Log.w(TAG, "Cache HIT but URL dead for $channelId ($cachedUrl) — refreshing Firestore")
                prefs.edit().remove(urlKey(channelId)).apply()
                return@withContext fetchFromFirestore(context, channelId)
            }

            Log.d(TAG, "Cache MISS for $channelId — fetching Firestore (first time)")
            fetchFromFirestore(context, channelId)
        }

    /**
     * Force a fresh Firestore fetch, clearing the stale cache entry first.
     *
     * Returns:
     *   String  → fresh reachable URL
     *   null    → Firestore URL also offline, stream truly ended
     */
    suspend fun fetchFreshAndCache(context: Context, channelId: String): String? =
        withContext(Dispatchers.IO) {
            if (channelId.isBlank()) return@withContext null

            Log.w(TAG, "Invalidating stale cache for $channelId — re-fetching Firestore")
            prefs(context).edit().remove(urlKey(channelId)).apply()

            fetchFromFirestore(context, channelId)
        }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL HELPERS
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetches liveUrl from Firestore, validates it with isUrlReachable, caches if alive.
     *
     * Returns:
     *   String  → URL is reachable, cached and ready
     *   null    → Firestore URL exists but is not reachable (stream offline),
     *             OR Firestore doc missing liveUrl field,
     *             OR Firestore network error
     *
     * NOTE: We do NOT cache when the URL is unreachable. This ensures the next tap
     * will retry Firestore rather than serving a known-dead cached URL.
     */
    private suspend fun fetchFromFirestore(context: Context, channelId: String): String? {
        return try {
            val db  = FirebaseFirestore.getInstance()
            val doc = db.collection("mosques").document(channelId).get().await()
            val url = doc.getString("liveUrl")?.takeIf { it.isNotBlank() }

            if (url == null) {
                Log.w(TAG, "Firestore doc '$channelId' has no 'liveUrl' field — returning null")
                return null
            }

            // Validate the Firestore URL before trusting it
            if (isUrlReachable(url)) {
                prefs(context).edit().putString(urlKey(channelId), url).apply()
                Log.d(TAG, "Firestore OK + reachable for $channelId -> $url (cached)")
                url
            } else {
                // Firestore URL also dead → stream truly offline
                // Do NOT cache so next tap retries Firestore
                Log.w(TAG, "Firestore URL unreachable for $channelId ($url) — stream offline, returning null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore fetch failed for $channelId", e)
            null  // Network error — do not cache, let user retry
        }
    }

    /**
     * HEAD request to check if a URL returns HTTP < 400.
     * YouTube watch?v=XXXX for an ended stream returns HTTP 404.
     * On timeout/network error, returns true (fail-open) to avoid blocking user.
     */
    private fun isUrlReachable(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod           = "HEAD"
            conn.connectTimeout          = REACHABILITY_TIMEOUT_MS
            conn.readTimeout             = REACHABILITY_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "HEAD $url -> HTTP $code")
            code < 400
        } catch (e: Exception) {
            Log.w(TAG, "HEAD check failed for $url (${e.message}) — assuming reachable")
            true  // Fail-open: don't block user on flaky network
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun urlKey(channelId: String) = "yt_url_$channelId"
}