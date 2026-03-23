package com.abang.prayerzones.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.location.Location
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.abang.prayerzones.R
import com.abang.prayerzones.model.InMosqueMode
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * InMosqueModeManager
 *
 * Provided as @Singleton by AppModule — do NOT add @Inject constructor.
 *
 * Responsible for:
 * 1. Applying the user's chosen audio profile when entering a mosque geofence.
 * 2. Saving and restoring the previous ringer state on exit.
 * 3. Registering an AudioModeReceiver + ContentObserver to detect manual volume/ringer
 *    overrides while active (fixes Samsung volume-key detection).
 * 4. Running a stay-check timer anchored to T+pref_near_prayer_time of the prayer time.
 * 5. When feature toggle is OFF (DISABLED): posting a manual proximity reminder
 *    notification without touching the ringer or setting PREF_ACTIVE.
 */
class InMosqueModeManager(private val context: Context) {

    companion object {
        private const val TAG                  = "InMosqueModeManager"
        private const val STAY_CHANNEL_ID      = "in_mosque_stay_channel"
        private const val STAY_NOTIF_ID        = 7100
        private const val DISABLED_CHANNEL_ID  = "in_mosque_disabled_channel"
        private const val DISABLED_NOTIF_ID    = 7103
        const val ACTION_STAY_KEEP             = "com.abang.prayerzones.IN_MOSQUE_STAY_KEEP"
        const val ACTION_STAY_EXIT             = "com.abang.prayerzones.IN_MOSQUE_STAY_EXIT"
        const val ACTION_DISABLED_DISMISS      = "com.abang.prayerzones.IN_MOSQUE_DISABLED_DISMISS"

        // Window-based enforcement constants
        const val EXTRA_WINDOW_END_TIME        = "window_end_time_ms"
        /** SharedPreferences key – true while a prayer window is open. */
        const val PREF_WITHIN_WINDOW           = "in_mosque_within_window"
        private const val LOCATION_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val STAY_HERE_CHANNEL_ID = "in_mosque_stay_here_channel"
        private const val STAY_HERE_NOTIF_ID   = 7105
        const val ACTION_STAY_HERE_DISMISS     = "com.abang.prayerzones.IN_MOSQUE_STAY_HERE_DISMISS"

        /** SharedPreferences key – true while the "Still at Mosque" card is showing. */
        const val PREF_SHOW_STAY_CARD          = "in_mosque_show_stay_card"
        /** Timestamp (epoch ms) when the stay card was posted. Used for auto-expiry. */
        private const val PREF_STAY_CARD_POSTED_AT = "in_mosque_stay_card_posted_at"
        /** Auto-expire the stay card 10 minutes after the window ended. */
        private const val STAY_CARD_EXPIRY_MS  = 10 * 60 * 1000L  // 10 minutes
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs        = PreferenceManager.getDefaultSharedPreferences(context)
    private val mainHandler  = Handler(Looper.getMainLooper())
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var audioModeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver?      = null
    private var locationCheckRunnable: Runnable?      = null
    private var cancellationTokenSource: CancellationTokenSource? = null
    private var stayTimerRunnable: Runnable?           = null
    private var stayCardExpiryRunnable: Runnable?      = null

    /**
     * Guard flag to prevent the AudioModeReceiver / ContentObserver from
     * interpreting the app's own ringer/volume changes as user overrides.
     * Set to `true` before any programmatic audio change, reset after 2 s.
     */
    @Volatile
    private var isInternalChange = false

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API - WINDOW-BASED ENFORCEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called at Window Start (PrayerTime - NearPrayerTime).
     * Performs immediate location check and schedules recurring checks if needed.
     */
    fun onWindowStart(
        mosqueName: String,
        mosqueId: String,
        mosqueLat: Double,
        mosqueLng: Double,
        windowEndTimeMs: Long
    ) {
        Log.d(TAG, "⏰ Window START for $mosqueName (will end at ${java.util.Date(windowEndTimeMs)})")

        // Store window info for restoration (including lat/lng for post-window proximity check)
        // TASK 3: commit() ensures values are on disk before any audio changes
        prefs.edit()
            .putString(InMosqueMode.PREF_MOSQUE_NAME, mosqueName)
            .putString(InMosqueMode.PREF_MOSQUE_ID, mosqueId)
            .putFloat("pref_last_mosque_lat", mosqueLat.toFloat())
            .putFloat("pref_last_mosque_lng", mosqueLng.toFloat())
            .putLong(EXTRA_WINDOW_END_TIME, windowEndTimeMs)
            .putBoolean(PREF_WITHIN_WINDOW, true)
            .commit()

        // TASK 2: Force-sync with a fresh high-priority location request
        // so the transition to Green happens within seconds of the alarm firing.
        performForcedLocationSync(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
    }

    /**
     * TASK 2: Force-sync a fresh location fix when the prayer-window alarm fires.
     *
     * Uses [CurrentLocationRequest] with tight freshness constraints so the system
     * will NOT return a stale cached location.  If the fresh fix fails (GPS cold,
     * no permission, etc.), falls back to the standard [performLocationCheck] which
     * has its own lastLocation fallback + 5-min recurring schedule.
     */
    private fun performForcedLocationSync(
        mosqueName: String,
        mosqueId: String,
        mosqueLat: Double,
        mosqueLng: Double,
        windowEndTimeMs: Long
    ) {
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            )) {
            Log.w(TAG, "🚀 Force-sync: no location permission — falling back to standard check")
            performLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
            return
        }

        Log.d(TAG, "🚀 Force-sync: requesting fresh high-priority location for onWindowStart")

        val cts = CancellationTokenSource()
        cancellationTokenSource = cts

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5_000L)   // reject locations older than 5 s
            .setDurationMillis(15_000L)      // wait up to 15 s for a fresh GPS fix
            .build()

        @Suppress("MissingPermission")
        fusedLocationClient.getCurrentLocation(request, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val age = (System.currentTimeMillis() - location.time) / 1000
                    Log.d(TAG, "🚀 Force-sync: fresh location obtained (age=${age}s)")
                    handleLocationResult(location, mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                } else {
                    Log.w(TAG, "🚀 Force-sync: null — falling back to standard check")
                    performLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "🚀 Force-sync failed: ${e.message} — falling back to standard check")
                performLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
            }
    }

    /**
     * Called at Window End (PrayerTime + NearPrayerTime).
     * MANDATORY restoration regardless of location.
     */
    fun onWindowEnd() {
        Log.d(TAG, "⏰ Window END - performing mandatory restoration")

        // Cancel any pending location checks and stay card timers
        cancelLocationChecks()
        cancelStayTimer()
        cancelStayCardExpiry()

        // Remember mosque info before clearing for the post-window proximity check
        val mosqueName = prefs.getString(InMosqueMode.PREF_MOSQUE_NAME, null)
        val mosqueId   = prefs.getString(InMosqueMode.PREF_MOSQUE_ID, null)
        val savedLat   = prefs.getFloat("pref_last_mosque_lat", 0f).toDouble()
        val savedLng   = prefs.getFloat("pref_last_mosque_lng", 0f).toDouble()

        // Restore original audio state — guard against AudioModeReceiver treating this as user override
        isInternalChange = true
        restoreOriginalState()
        mainHandler.postDelayed({ isInternalChange = false }, 2000L)

        // Clear window data — but do NOT clear PREF_SHOW_STAY_CARD here.
        // The stay card is "sticky": it should only disappear if:
        //   1. The user taps "OK", OR
        //   2. The distance to mosque > pref_min_distance_mosque (checked in handleLocationResult)
        // Reset PREF_LAST_DISTANCE to MAX_VALUE so the UI color matrix returns
        // to Normal (Unspecified) immediately. The post-window proximity check
        // will write a fresh distance if the user is still near the mosque.
        prefs.edit()
            .remove(EXTRA_WINDOW_END_TIME)
            .putBoolean(InMosqueMode.PREF_ACTIVE, false)
            .putBoolean(PREF_WITHIN_WINDOW, false)
            .putFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
            .remove(InMosqueMode.PREF_MOSQUE_NAME)
            .remove(InMosqueMode.PREF_MOSQUE_ID)
            .remove("pref_last_mosque_lat")
            .remove("pref_last_mosque_lng")
            .commit()    // commit() — synchronous so UI picks up the reset immediately

        unregisterAudioModeReceiver()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(STAY_NOTIF_ID)
        nm.cancel(DISABLED_NOTIF_ID)
        nm.cancel(STAY_HERE_NOTIF_ID)

        // Post-window "Still Here" check: if user is still near mosque, show a reminder
        if (mosqueName != null && (savedLat != 0.0 || savedLng != 0.0)) {
            performPostWindowProximityCheckWithCoords(mosqueName, savedLat, savedLng)
        } else if (mosqueName != null) {
            Log.d(TAG, "Post-window: no saved coords — skipping proximity check for $mosqueName")
        }
    }

    /**
     * Legacy method for compatibility - redirects to window-based logic
     */
    @Deprecated("Use onWindowStart instead", ReplaceWith("onWindowStart"))
    fun onEnterMosque(mosqueName: String, mosqueId: String, prayerTimeUtcMillis: Long = 0L) {
        // Keep for backward compatibility during transition
        val chosenMode = InMosqueMode.fromPref(
            prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
        )

        if (chosenMode == InMosqueMode.DISABLED) {
            Log.d(TAG, "Mode DISABLED — posting manual reminder for $mosqueName")
            postManualReminderNotification(mosqueName)
            return
        }

        // Guard: only silence phone if within a prayer window
        val withinWindow = prefs.getBoolean(PREF_WITHIN_WINDOW, false)
        if (!withinWindow) {
            Log.d(TAG, "onEnterMosque: User near $mosqueName but OUTSIDE prayer window — skipping audio change")
            return
        }

        Log.d(TAG, "Entering mosque: $mosqueName | mode=$chosenMode")

        if (!prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) {
            saveOriginalAudioState()
        }

        prefs.edit()
            .putBoolean(InMosqueMode.PREF_ACTIVE, true)
            .putString(InMosqueMode.PREF_MOSQUE_NAME, mosqueName)
            .putString(InMosqueMode.PREF_MOSQUE_ID, mosqueId)
            .apply()

        applyMode(chosenMode, mosqueName)
        registerAudioModeReceiver(mosqueName)
    }

    @Deprecated("Use onWindowEnd instead", ReplaceWith("onWindowEnd"))
    fun onExitMosque() {
        onWindowEnd()
    }

    fun isActive(): Boolean = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)

    /**
     * Called when the user explicitly toggles the In-Mosque feature OFF from the UI.
     * Performs a FULL cleanup: restores audio state, clears all flags, cancels all
     * pending timers/checks, and unregisters receivers. Guarantees the phone returns
     * to its pre-mosque audio state even if the user is still physically at the mosque.
     */
    fun disableAndRestore() {
        Log.d(TAG, "🔴 Feature toggled OFF — performing full cleanup & restoration")

        // Cancel all pending work
        cancelLocationChecks()
        cancelStayTimer()
        cancelStayCardExpiry()

        // Restore audio only if the feature was actively controlling it
        if (prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) {
            isInternalChange = true
            restoreOriginalState()
            mainHandler.postDelayed({ isInternalChange = false }, 2000L)
        }

        // Clear all In-Mosque state (including stay card flags and stale distance)
        prefs.edit()
            .putBoolean(InMosqueMode.PREF_ACTIVE, false)
            .putBoolean(PREF_WITHIN_WINDOW, false)
            .putBoolean(PREF_SHOW_STAY_CARD, false)
            .putFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
            .remove(PREF_STAY_CARD_POSTED_AT)
            .remove(EXTRA_WINDOW_END_TIME)
            .remove(InMosqueMode.PREF_MOSQUE_NAME)
            .remove(InMosqueMode.PREF_MOSQUE_ID)
            .commit()

        // Unregister all observers/receivers
        unregisterAudioModeReceiver()

        // Dismiss all pending notifications
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(STAY_NOTIF_ID)
        nm.cancel(DISABLED_NOTIF_ID)
        nm.cancel(STAY_HERE_NOTIF_ID)

        Log.d(TAG, "✅ Full cleanup complete — phone restored to original state")
    }

    /**
     * Check if user is currently within the prayer window
     */
    fun isWithinWindow(): Boolean {
        val windowEndTime = prefs.getLong(EXTRA_WINDOW_END_TIME, 0L)
        return windowEndTime > 0L && System.currentTimeMillis() < windowEndTime
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API — STAY CARD MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dismiss the "Still at Mosque" card from both SharedPreferences and the
     * notification shade.  Called by the ViewModel when the user taps OK or
     * when the sanity-check determines the card is stale.
     */
    fun dismissStayCard() {
        Log.d(TAG, "Dismissing stay card")
        cancelStayCardExpiry()
        prefs.edit()
            .putBoolean(PREF_SHOW_STAY_CARD, false)
            .remove(PREF_STAY_CARD_POSTED_AT)
            .apply()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(STAY_HERE_NOTIF_ID)
    }

    /**
     * Sanity check — should be called on app resume / foreground.
     * If the card was posted more than 10 minutes ago, auto-dismiss it.
     *
     * The card is "sticky": it survives window end (PREF_ACTIVE=false,
     * EXTRA_WINDOW_END_TIME removed).  It should only disappear if:
     *   1. User taps "OK"  (dismissStayCard)
     *   2. 10-minute expiry since posting
     *   3. User physically leaves the mosque area (distance > near threshold,
     *      handled in handleLocationResult / the UI layer)
     */
    fun performStayCardSanityCheck() {
        val showing = prefs.getBoolean(PREF_SHOW_STAY_CARD, false)
        if (!showing) return

        val postedAt = prefs.getLong(PREF_STAY_CARD_POSTED_AT, 0L)
        val elapsed  = System.currentTimeMillis() - postedAt

        if (postedAt > 0L && elapsed > STAY_CARD_EXPIRY_MS) {
            Log.d(TAG, "Stay card expired (${elapsed / 60_000}min > ${STAY_CARD_EXPIRY_MS / 60_000}min) — auto-dismissing")
            dismissStayCard()
            return
        }

        // Note: Do NOT auto-dismiss based on windowEnd == 0 or isActive == false.
        // onWindowEnd() intentionally clears both while the card should remain visible.
    }

    /**
     * Schedule an auto-expiry for the stay card. If the user never taps "OK",
     * it will be automatically dismissed after [STAY_CARD_EXPIRY_MS].
     */
    private fun scheduleStayCardExpiry() {
        cancelStayCardExpiry()
        stayCardExpiryRunnable = Runnable {
            Log.d(TAG, "Stay card auto-expiry timer fired — dismissing")
            dismissStayCard()
        }
        mainHandler.postDelayed(stayCardExpiryRunnable!!, STAY_CARD_EXPIRY_MS)
        Log.d(TAG, "Scheduled stay card auto-expiry in ${STAY_CARD_EXPIRY_MS / 60_000} minutes")
    }

    private fun cancelStayCardExpiry() {
        stayCardExpiryRunnable?.let { mainHandler.removeCallbacks(it) }
        stayCardExpiryRunnable = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — LOCATION CHECKING
    // ─────────────────────────────────────────────────────────────────────────

    private fun performLocationCheck(
        mosqueName: String,
        mosqueId: String,
        mosqueLat: Double,
        mosqueLng: Double,
        windowEndTimeMs: Long
    ) {
        try {
            // Check if we still have permission
            if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                Log.w(TAG, "Location permission not granted, skipping proximity check")
                return
            }

            // Create new cancellation token (replaces any previous one)
            cancellationTokenSource = CancellationTokenSource()

            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource!!.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    handleLocationResult(location, mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                } else {
                    // Fallback: try lastLocation before scheduling 5-min recurring check
                    Log.w(TAG, "getCurrentLocation returned null, trying lastLocation fallback")
                    @Suppress("MissingPermission")
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            Log.d(TAG, "Using lastLocation fallback (age=${(System.currentTimeMillis() - lastLoc.time) / 1000}s)")
                            handleLocationResult(lastLoc, mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                        } else {
                            Log.w(TAG, "lastLocation also null — scheduling recurring check")
                            scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                        }
                    }.addOnFailureListener {
                        Log.e(TAG, "lastLocation fallback failed", it)
                        scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                    }
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to get location", e)
                // On failure, try lastLocation before scheduling recurring check
                @Suppress("MissingPermission")
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        handleLocationResult(lastLoc, mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                    } else {
                        scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                    }
                }.addOnFailureListener {
                    scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting location", e)
        }
    }

    private fun handleLocationResult(
        location: Location,
        mosqueName: String,
        mosqueId: String,
        mosqueLat: Double,
        mosqueLng: Double,
        windowEndTimeMs: Long
    ) {
        // ── FIX 1: Location Freshness Guard ──
        // Ignore stale location data older than 60 seconds to prevent the app
        // from turning Green based on where the user was this morning.
        val locationAgeMs = System.currentTimeMillis() - location.time
        if (locationAgeMs > 60_000L) {
            Log.w(TAG, "📍 Ignoring stale location (age=${locationAgeMs / 1000}s > 60s). " +
                    "Scheduling recurring check for fresh data.")
            scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
            return
        }

        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            mosqueLat, mosqueLng,
            results
        )
        val distanceMeters = results[0]
        val thresholdMeters = prefs.getString(
            "pref_min_distance_mosque",
            InMosqueMode.DEFAULT_MIN_DISTANCE_METERS
        )!!.toFloat()

        // Persist distance so the UI layer can derive accent color.
        // Only write if the feature toggle is ON to prevent ghost writes.
        val modeEnabled = InMosqueMode.fromPref(
            prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
        ) != InMosqueMode.DISABLED
        if (modeEnabled) {
            prefs.edit()
                .putFloat(InMosqueMode.PREF_LAST_DISTANCE, distanceMeters)
                .apply()
        }

        Log.d(TAG, "📍 Distance to $mosqueName: ${distanceMeters.toInt()}m (threshold: ${thresholdMeters.toInt()}m)")
        Log.d("IN_MOSQUE_DEBUG", "Distance: ${distanceMeters.toInt()}m, Threshold: ${thresholdMeters.toInt()}m. Calling onProximityDetected? ${distanceMeters <= thresholdMeters}")

        if (distanceMeters <= thresholdMeters) {
            // User IS inside proximity threshold
            onProximityDetected(mosqueName, mosqueId)
        } else {
            // User is NOT inside — auto-dismiss "Still Here" card if it's showing
            if (prefs.getBoolean(PREF_SHOW_STAY_CARD, false)) {
                Log.d(TAG, "User left mosque area (${distanceMeters.toInt()}m > ${thresholdMeters.toInt()}m) — auto-dismissing stay card")
                dismissStayCard()
            }

            // Schedule recurring checks
            Log.d(TAG, "User outside threshold, scheduling recurring checks every 5min")
            scheduleRecurringLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
        }
    }

    private fun onProximityDetected(mosqueName: String, mosqueId: String) {
        val chosenMode = InMosqueMode.fromPref(
            prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
        )

        Log.d("IN_MOSQUE_DEBUG", "onProximityDetected: mosqueName=$mosqueName, chosenMode=$chosenMode")

        if (chosenMode == InMosqueMode.DISABLED) {
            Log.d(TAG, "📵 DISABLED mode — showing reminder notification")
            Log.d("IN_MOSQUE_DEBUG", "Mode is DISABLED — NOT setting PREF_ACTIVE, showing reminder only")
            postManualReminderNotification(mosqueName)
            return
        }

        // ── CRITICAL GUARD: Only silence the phone if we are INSIDE a prayer window ──
        // Without this, toggling the switch ON while at the mosque but outside a prayer
        // window would immediately silence the phone and turn the UI Green.
        val withinWindow = prefs.getBoolean(PREF_WITHIN_WINDOW, false)
        if (!withinWindow) {
            Log.d(TAG, "📍 User is near $mosqueName but OUTSIDE prayer window — staying Orange (standby). " +
                    "Audio will be applied when the next prayer window opens.")
            Log.d("IN_MOSQUE_DEBUG", "PREF_WITHIN_WINDOW=false — NOT setting PREF_ACTIVE, NOT applying mode")
            return
        }

        // User is inside AND within a prayer window — apply Quiet Mode
        Log.d(TAG, "🕌 User inside $mosqueName during prayer window — applying Quiet Mode: $chosenMode")

        // Save original state if not already saved
        if (!prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) {
            saveOriginalAudioState()
        }

        // CRITICAL: Set PREF_ACTIVE BEFORE applyMode() so UI turns Green immediately
        Log.d("IN_MOSQUE_DEBUG", "Setting PREF_ACTIVE to TRUE (before applyMode)")
        prefs.edit()
            .putBoolean(InMosqueMode.PREF_ACTIVE, true)
            .commit()  // Use commit() for synchronous write — UI listener fires faster

        // ── HAPTIC CONFIRMATION: distinct double-pulse so the user *feels* Green ──
        vibrateDoublePulse()

        applyMode(chosenMode, mosqueName)
        registerAudioModeReceiver(mosqueName)

        // Cancel recurring location checks since we're now active
        cancelLocationChecks()

        // ── SAFETY HEARTBEAT: schedule a one-shot guard at windowEndTime ──
        // If the AlarmManager END alarm fails (OEM kill, requestCode collision, etc.),
        // this ensures PREF_WITHIN_WINDOW does not become a zombie flag.
        val windowEndMs = prefs.getLong(EXTRA_WINDOW_END_TIME, 0L)
        if (windowEndMs > 0L) {
            val delay = windowEndMs - System.currentTimeMillis() + 2_000L // 2s after window end
            if (delay > 0) {
                val guardRunnable = Runnable {
                    val stillWithin = prefs.getBoolean(PREF_WITHIN_WINDOW, false)
                    val stillActive = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)

                    // TASK 3 GUARD: Re-read the CURRENT window end time.
                    // If the user pressed "Keep ON" (which extends the window), the
                    // stored end time will be later than the original.  Only force
                    // cleanup when the current window end is truly in the past.
                    val currentWindowEnd = prefs.getLong(EXTRA_WINDOW_END_TIME, 0L)
                    val windowExpired = currentWindowEnd > 0L &&
                            System.currentTimeMillis() > currentWindowEnd

                    if ((stillWithin || stillActive) && windowExpired) {
                        Log.w(TAG, "🛡️ SAFETY HEARTBEAT: Window expired but flags still set. " +
                                "Forcing onWindowEnd() [PREF_WITHIN_WINDOW=$stillWithin, PREF_ACTIVE=$stillActive]")
                        onWindowEnd()
                    } else if (stillWithin || stillActive) {
                        Log.d(TAG, "🛡️ Safety heartbeat: flags still set but window was extended — skipping")
                    }
                }
                mainHandler.postDelayed(guardRunnable, delay)
                Log.d(TAG, "🛡️ Scheduled safety heartbeat in ${delay / 60_000}min (at ${java.util.Date(windowEndMs + 2000)})")
            }
        }
    }

    private fun scheduleRecurringLocationCheck(
        mosqueName: String,
        mosqueId: String,
        mosqueLat: Double,
        mosqueLng: Double,
        windowEndTimeMs: Long
    ) {
        // Cancel any existing recurring check
        cancelLocationChecks()

        locationCheckRunnable = Runnable {
            // Check if window is still active
            if (System.currentTimeMillis() < windowEndTimeMs) {
                performLocationCheck(mosqueName, mosqueId, mosqueLat, mosqueLng, windowEndTimeMs)
            } else {
                Log.d(TAG, "Window ended during recurring check")
                onWindowEnd()
            }
        }

        mainHandler.postDelayed(locationCheckRunnable!!, LOCATION_CHECK_INTERVAL_MS)
        Log.d(TAG, "⏱️ Scheduled next location check in 5 minutes")
    }

    private fun cancelLocationChecks() {
        locationCheckRunnable?.let {
            mainHandler.removeCallbacks(it)
            locationCheckRunnable = null
        }
        // Note: CancellationTokenSource doesn't need explicit cleanup
        cancellationTokenSource = null
    }

    private fun saveOriginalAudioState() {
        val currentRinger = audioManager.ringerMode
        val originalRing = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val originalNotif = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val originalMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // TASK 3: commit() — atomic synchronous write ensures snapshot is on disk
        // BEFORE any ringer mode or volume changes are applied.
        prefs.edit()
            .putInt(InMosqueMode.PREF_SAVED_RINGER, currentRinger)
            .putInt(InMosqueMode.PREF_SAVED_RING_VOL, originalRing)
            .putInt(InMosqueMode.PREF_SAVED_NOTIF_VOL, originalNotif)
            .putInt(InMosqueMode.PREF_SAVED_MUSIC_VOL, originalMusic)
            .commit()

        Log.d(TAG, "💾 Saved original state (commit): ringer=$currentRinger, ring=$originalRing, notif=$originalNotif, music=$originalMusic")
    }

    private fun restoreOriginalState() {
        Log.d(TAG, "🔄 Restoring original audio state")

        // Restore ringer mode
        val savedRinger = prefs.getInt(InMosqueMode.PREF_SAVED_RINGER, AudioManager.RINGER_MODE_NORMAL)
        try {
            audioManager.ringerMode = savedRinger
            Log.d(TAG, "Restored ringer mode: $savedRinger")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not restore ringer mode (DND policy?)", e)
        }

        // Restore volumes
        restoreVolumes()

        // Clear saved state — TASK 3: use commit() for reliable write
        prefs.edit()
            .remove(InMosqueMode.PREF_SAVED_RINGER)
            .commit()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — APPLY MODE
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyMode(mode: InMosqueMode, mosqueName: String) {
        when (mode) {
            InMosqueMode.SILENT -> {
                if (!hasDndAccess()) {
                    ToastUtils.show(context, "⚠️ Permission Required: Please allow Do Not Disturb access for PrayerZones")
                    Log.w(TAG, "DND access not granted — cannot set SILENT mode")
                    return
                }
                isInternalChange = true
                setRingerSafe(AudioManager.RINGER_MODE_SILENT)
                mainHandler.postDelayed({ isInternalChange = false }, 2000L)
                ToastUtils.show(context, "🔇 Silent mode active at $mosqueName")
            }
            InMosqueMode.VIBRATE -> {
                if (!hasDndAccess()) {
                    ToastUtils.show(context, "⚠️ Permission Required: Please allow Do Not Disturb access for PrayerZones")
                    Log.w(TAG, "DND access not granted — cannot set VIBRATE mode")
                    return
                }
                isInternalChange = true
                setRingerSafe(AudioManager.RINGER_MODE_VIBRATE)
                mainHandler.postDelayed({ isInternalChange = false }, 2000L)
                vibrateOnce()
                ToastUtils.show(context, "📳 Vibrate mode active at $mosqueName")
            }
            InMosqueMode.LOWER_VOLUME -> {
                applyLowerVolume(mosqueName)
                ToastUtils.show(context, "🔉 Volume lowered at $mosqueName")
            }
            InMosqueMode.DISABLED -> { /* handled before applyMode is called */
                // Ensure clean transition: explicitly set to NORMAL first to clear any SILENT flags
                try {
                    isInternalChange = true
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    mainHandler.postDelayed({ isInternalChange = false }, 2000L)
                } catch (e: SecurityException) {
                    isInternalChange = false
                    Log.w(TAG, "Cannot clear ringer mode flags", e)
                }
            }
        }
    }

    /**
     * Check if the app has Do Not Disturb (Notification Policy) access.
     * Required for setting RINGER_MODE_SILENT or RINGER_MODE_VIBRATE on Android 6+.
     */
    private fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun setRingerSafe(mode: Int) {
        try {
            audioManager.ringerMode = mode
        } catch (e: SecurityException) {
            isInternalChange = false
            Log.w(TAG, "Cannot set ringer mode — DND policy active", e)
        }
    }

    private fun vibrateOnce() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> vibrateApi31()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrateApi26()
            else                                           -> vibrateLegacy()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun vibrateApi31() {
        val vm = context.getSystemService(VibratorManager::class.java)
        vm?.defaultVibrator?.vibrate(
            VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun vibrateApi26() {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @Suppress("DEPRECATION")
    private fun vibrateLegacy() {
        val vibrator = context.getSystemService(android.os.Vibrator::class.java)
        vibrator?.vibrate(400)
    }

    /**
     * Haptic confirmation for the Green (Active Mode) transition.
     *
     * Uses a distinct "double pulse" pattern — [0, 200, 100, 200] — that feels
     * noticeably different from a standard notification buzz.  On API 26+ the
     * waveform is created with [VibrationEffect]; on older devices the legacy
     * [android.os.Vibrator.vibrate(long[], int)] API is used.
     *
     * The pattern means: wait 0 ms, vibrate 200 ms, pause 100 ms, vibrate 200 ms.
     */
    private fun vibrateDoublePulse() {
        val pattern = longArrayOf(0, 200, 100, 200)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val vm = context.getSystemService(VibratorManager::class.java)
                    vm?.defaultVibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, -1) // -1 = no repeat
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    vibrator?.vibrate(
                        VibrationEffect.createWaveform(pattern, -1)
                    )
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
            Log.d(TAG, "🟢 Haptic double-pulse fired (Green transition)")
        } catch (e: Exception) {
            Log.w(TAG, "Haptic double-pulse failed: ${e.message}")
        }
    }

    /**
     * Lower Volume Mode: Reduce RING, NOTIFICATION, and MUSIC volumes by
     * pref_lower_volume_percent (default ${InMosqueMode.DEFAULT_LOWER_VOLUME_PCT}%). ALARM stream is untouched to
     * protect app prayer alerts.
     */
    private fun applyLowerVolume(mosqueName: String) {
        val percent = prefs.getString("pref_lower_volume_percent", InMosqueMode.DEFAULT_LOWER_VOLUME_PCT)!!.toInt()

        // Only save original volumes if NOT already active (prevents overwriting
        // true originals with already-lowered values during "Keep ON" re-entry)
        if (!prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) {
            val originalRing = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val originalNotif = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            val originalMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // TASK 3: commit() — atomic write BEFORE volumes are modified
            prefs.edit()
                .putInt(InMosqueMode.PREF_SAVED_RING_VOL, originalRing)
                .putInt(InMosqueMode.PREF_SAVED_NOTIF_VOL, originalNotif)
                .putInt(InMosqueMode.PREF_SAVED_MUSIC_VOL, originalMusic)
                .commit()
            Log.d(TAG, "💾 Saved original volumes (commit): Ring=$originalRing, Notif=$originalNotif, Music=$originalMusic")
        } else {
            Log.d(TAG, "⚡ PREF_ACTIVE already true — skipping volume save to protect originals")
        }

        // Calculate lowered volumes
        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val newRing = (maxRing * percent / 100).coerceAtLeast(1)
        val newNotif = (maxNotif * percent / 100).coerceAtLeast(1)
        val newMusic = (maxMusic * percent / 100).coerceAtLeast(1)

        // Apply lowered volumes (ALARM stream is deliberately excluded)
        try {
            isInternalChange = true // Re-assert guard for volume-specific observer
            audioManager.setStreamVolume(AudioManager.STREAM_RING, newRing, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, newNotif, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newMusic, 0)
            mainHandler.postDelayed({ isInternalChange = false }, 2000L)
            Log.d(TAG, "Lowered volumes to $percent% at $mosqueName (Ring→$newRing, Notif→$newNotif, Music→$newMusic)")
        } catch (e: SecurityException) {
            isInternalChange = false
            Log.w(TAG, "Could not lower volumes (DND policy?)", e)
        }
    }

    /**
     * Restore volumes when exiting mosque (only for LOWER_VOLUME mode).
     * Uses precise saved snapshots; falls back to 50% of max if snapshots are missing.
     */
    private fun restoreVolumes() {
        val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Fallback to 50% of max if no snapshot was saved
        val fallbackRing = (maxRing * 50 / 100).coerceAtLeast(1)
        val fallbackNotif = (maxNotif * 50 / 100).coerceAtLeast(1)
        val fallbackMusic = (maxMusic * 50 / 100).coerceAtLeast(1)

        val hasSavedSnapshot = prefs.contains(InMosqueMode.PREF_SAVED_RING_VOL)

        val targetRing: Int
        val targetNotif: Int
        val targetMusic: Int

        if (hasSavedSnapshot) {
            targetRing = prefs.getInt(InMosqueMode.PREF_SAVED_RING_VOL, fallbackRing)
            targetNotif = prefs.getInt(InMosqueMode.PREF_SAVED_NOTIF_VOL, fallbackNotif)
            targetMusic = prefs.getInt(InMosqueMode.PREF_SAVED_MUSIC_VOL, fallbackMusic)
            Log.d(TAG, "🔄 Restoring precise volume snapshots: Ring=$targetRing, Notif=$targetNotif, Music=$targetMusic")
        } else {
            targetRing = fallbackRing
            targetNotif = fallbackNotif
            targetMusic = fallbackMusic
            Log.w(TAG, "⚠️ No saved volume snapshots found — falling back to 50%: Ring=$targetRing, Notif=$targetNotif, Music=$targetMusic")
        }

        try {
            isInternalChange = true
            audioManager.setStreamVolume(AudioManager.STREAM_RING, targetRing, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetNotif, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
            mainHandler.postDelayed({ isInternalChange = false }, 2000L)
            Log.d(TAG, "✅ Volumes restored (Ring:$targetRing, Notif:$targetNotif, Music:$targetMusic)")

            // TASK 3: Only clear saved volumes AFTER successful restoration.
            // Use commit() so keys are reliably removed; on failure the snapshot
            // is preserved for a future retry.
            prefs.edit()
                .remove(InMosqueMode.PREF_SAVED_RING_VOL)
                .remove(InMosqueMode.PREF_SAVED_NOTIF_VOL)
                .remove(InMosqueMode.PREF_SAVED_MUSIC_VOL)
                .commit()
        } catch (e: SecurityException) {
            isInternalChange = false
            Log.w(TAG, "Could not restore volumes — snapshot keys preserved for retry", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — MANUAL REMINDER NOTIFICATION (feature toggle OFF)
    // Posted when the user enters the geofence but has chosen to manage their
    // ringer manually (PREF_KEY == DISABLED). The geofence still fires so the
    // user gets a timely reminder — but nothing else changes.
    // ─────────────────────────────────────────────────────────────────────────

    private fun postManualReminderNotification(mosqueName: String) {
        ensureDisabledChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val dismissIntent = PendingIntent.getBroadcast(
            context, 7104,
            Intent(ACTION_DISABLED_DISMISS).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, DISABLED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🕌 You are near $mosqueName")
            .setContentText("You are within $mosqueName. Please remember to quiet your mobile accordingly.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("You are within $mosqueName. Please remember to quiet your mobile accordingly.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(
                NotificationCompat.Action(0, "✅ OK", dismissIntent)
            )
            .build()

        nm.notify(DISABLED_NOTIF_ID, notif)
        Log.d(TAG, "Posted disabled reminder for $mosqueName")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — AUDIO MODE OVERRIDE GUARD (BroadcastReceiver + ContentObserver)
    // Bug #4 fix: ContentObserver catches Samsung volume-key changes that don't
    // trigger RINGER_MODE_CHANGED_ACTION.
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerAudioModeReceiver(mosqueName: String) {
        // ── 1. BroadcastReceiver for ringer mode changes (Normal↔Vibrate↔Silent) ──
        if (audioModeReceiver == null) {
            audioModeReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (isInternalChange) {
                        Log.d(TAG, "Ignoring ringer broadcast — internal change in progress")
                        return
                    }
                    if (!prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) return
                    if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
                    Log.d(TAG, "User manually changed ringer mode — deactivating In-Mosque Mode")
                    deactivateByUserOverride(mosqueName)
                }
            }
            context.registerReceiver(
                audioModeReceiver,
                IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
            )
            Log.d(TAG, "AudioModeReceiver registered")
        }

        // ── 2. ContentObserver for volume changes (Samsung volume-key fix) ────────
        if (volumeObserver == null) {
            volumeObserver = object : ContentObserver(mainHandler) {
                private var lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (isInternalChange) {
                        // Update tracking value but don't treat as user override
                        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                        Log.d(TAG, "Ignoring volume change — internal change in progress")
                        return
                    }
                    if (!prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) return
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                    if (currentVolume != lastVolume) {
                        Log.d(TAG, "Volume changed ($lastVolume→$currentVolume) — deactivating In-Mosque Mode")
                        lastVolume = currentVolume
                        deactivateByUserOverride(mosqueName)
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )
            Log.d(TAG, "VolumeObserver registered")
        }
    }

    private fun deactivateByUserOverride(mosqueName: String) {
        prefs.edit().putBoolean(InMosqueMode.PREF_ACTIVE, false).apply()
        unregisterAudioModeReceiver()
        cancelStayTimer()
        ToastUtils.show(
            context,
            "You are within $mosqueName. Please remember to quiet your mobile accordingly."
        )
    }

    private fun unregisterAudioModeReceiver() {
        audioModeReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            audioModeReceiver = null
        }
        volumeObserver?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
            volumeObserver = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — STAY TIMER (anchored to prayerTime + pref_near_prayer_time)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedule the stay-check notification.
     * Fires at prayerTime + pref_near_prayer_time (resource-based default).
     * Falls back to pref_near_prayer_time from now if no prayer time is available.
     */
    private fun scheduleStayTimer(mosqueName: String, prayerTimeUtcMillis: Long) {
        cancelStayTimer()

        // Read window from pref_near_prayer_time
        val prefMinutes = prefs.getString("pref_near_prayer_time", InMosqueMode.DEFAULT_NEAR_PRAYER_MIN)!!.toLong()
        val windowMs = prefMinutes * 60 * 1000L

        val delay = if (prayerTimeUtcMillis > 0L) {
            val targetMs = prayerTimeUtcMillis + windowMs
            val computed = targetMs - System.currentTimeMillis()
            // Guard: if T+window is already in the past, use at least 1 minute from now
            if (computed > 0) computed else 60_000L
        } else {
            windowMs // fallback: pref_near_prayer_time minutes from now
        }

        stayTimerRunnable = Runnable {
            if (prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)) {
                postStayNotification(mosqueName)
            }
        }
        mainHandler.postDelayed(stayTimerRunnable!!, delay)
        Log.d(TAG, "Stay timer scheduled in ${delay / 60_000}min for $mosqueName " +
                "(anchor=prayerTime+${prefMinutes}min, prayerTimeUtcMillis=$prayerTimeUtcMillis)")
    }

    private fun cancelStayTimer() {
        stayTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        stayTimerRunnable = null
    }

    private fun postStayNotification(mosqueName: String) {
        ensureStayChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val keepIntent = PendingIntent.getBroadcast(
            context, 7101,
            Intent(ACTION_STAY_KEEP).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitIntent = PendingIntent.getBroadcast(
            context, 7102,
            Intent(ACTION_STAY_EXIT).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, STAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🕌 Still in $mosqueName")
            .setContentText("You are still in $mosqueName. Keep Quiet Mode ON?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .addAction(NotificationCompat.Action(0, "✅ Keep ON", keepIntent))
            .addAction(NotificationCompat.Action(0, "❌ Cancel",  exitIntent))
            .build()

        nm.notify(STAY_NOTIF_ID, notif)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — POST-WINDOW "STILL HERE" PROXIMITY CHECK
    // After the prayer window closes and ringer is restored, check if the
    // user is still physically near the mosque. If yes, post a one-time
    // reminder card so they know the automatic mode is off but they are
    // still at the mosque.
    // ─────────────────────────────────────────────────────────────────────────

    private fun performPostWindowProximityCheck(mosqueName: String) {
        try {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                )) {
                Log.w(TAG, "No location permission for post-window check")
                return
            }

            // Read the mosque coordinates from prefs (saved during window start)
            val savedLat = prefs.getFloat("pref_last_mosque_lat", 0f).toDouble()
            val savedLng = prefs.getFloat("pref_last_mosque_lng", 0f).toDouble()
            val thresholdMeters = prefs.getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()

            val cts = CancellationTokenSource()
            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cts.token
            ).addOnSuccessListener { location ->
                if (location == null) {
                    Log.d(TAG, "Post-window check: location null — skipping")
                    return@addOnSuccessListener
                }

                // Location freshness guard: ignore stale data (>60s old)
                val locationAgeMs = System.currentTimeMillis() - location.time
                if (locationAgeMs > 60_000L) {
                    Log.w(TAG, "Post-window: ignoring stale location (age=${locationAgeMs / 1000}s)")
                    return@addOnSuccessListener
                }

                if (savedLat != 0.0 || savedLng != 0.0) {
                    // We have saved mosque coords — perform distance check
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude, savedLat, savedLng, results
                    )
                    val distanceMeters = results[0]

                    // Persist fresh distance so UI (stay card gate + color matrix) has real data.
                    // Only write if the feature toggle is ON.
                    val modeEnabledPostWindow = InMosqueMode.fromPref(
                        prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                    ) != InMosqueMode.DISABLED
                    if (modeEnabledPostWindow) {
                        prefs.edit()
                            .putFloat(InMosqueMode.PREF_LAST_DISTANCE, distanceMeters)
                            .commit()
                    }

                    Log.d(TAG, "Post-window distance: ${distanceMeters.toInt()}m (threshold ${thresholdMeters.toInt()}m)")

                    if (distanceMeters <= thresholdMeters) {
                        postStillHereNotification(mosqueName)
                    } else {
                        Log.d(TAG, "Post-window: user already left mosque — no reminder needed")
                    }
                } else {
                    Log.d(TAG, "Post-window: no saved mosque coords — skipping reminder")
                }
            }.addOnFailureListener { e ->
                Log.w(TAG, "Post-window location check failed", e)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Post-window location security exception", e)
        }
    }

    /**
     * Overloaded version that accepts mosque coordinates for an accurate distance check.
     */
    fun onWindowEndWithCoords(mosqueLat: Double, mosqueLng: Double) {
        Log.d(TAG, "⏰ Window END (with coords) - performing mandatory restoration")

        cancelLocationChecks()
        cancelStayTimer()
        cancelStayCardExpiry()

        val mosqueName = prefs.getString(InMosqueMode.PREF_MOSQUE_NAME, null)
        val wasActive  = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)

        // Guard against AudioModeReceiver treating restoration as user override
        isInternalChange = true
        restoreOriginalState()
        mainHandler.postDelayed({ isInternalChange = false }, 2000L)

        prefs.edit()
            .remove(EXTRA_WINDOW_END_TIME)
            .putBoolean(InMosqueMode.PREF_ACTIVE, false)
            .putBoolean(PREF_WITHIN_WINDOW, false)
            .putFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
            .remove(InMosqueMode.PREF_MOSQUE_NAME)
            .remove(InMosqueMode.PREF_MOSQUE_ID)
            .remove("pref_last_mosque_lat")
            .remove("pref_last_mosque_lng")
            .commit()    // commit() — synchronous so UI picks up the reset immediately

        unregisterAudioModeReceiver()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(STAY_NOTIF_ID)
        nm.cancel(DISABLED_NOTIF_ID)
        // Note: do NOT cancel STAY_HERE_NOTIF_ID — the "Still Here" card is sticky

        // Post-window proximity check with known coordinates
        if (wasActive && mosqueName != null && (mosqueLat != 0.0 || mosqueLng != 0.0)) {
            performPostWindowProximityCheckWithCoords(mosqueName, mosqueLat, mosqueLng)
        }
    }

    private fun performPostWindowProximityCheckWithCoords(
        mosqueName: String, mosqueLat: Double, mosqueLng: Double
    ) {
        try {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                )) return

            val cts = CancellationTokenSource()
            @Suppress("MissingPermission")
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cts.token
            ).addOnSuccessListener { location ->
                if (location == null) return@addOnSuccessListener

                // Location freshness guard: ignore stale data (>60s old)
                val locationAgeMs = System.currentTimeMillis() - location.time
                if (locationAgeMs > 60_000L) {
                    Log.w(TAG, "Post-window: ignoring stale location (age=${locationAgeMs / 1000}s)")
                    return@addOnSuccessListener
                }

                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude, mosqueLat, mosqueLng, results
                )
                val distanceMeters  = results[0]
                val thresholdMeters = prefs.getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()

                // Persist fresh distance so UI (stay card gate + color matrix) has real data.
                // Only write if the feature toggle is ON.
                val modeEnabled = InMosqueMode.fromPref(
                    prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                ) != InMosqueMode.DISABLED
                if (modeEnabled) {
                    prefs.edit()
                        .putFloat(InMosqueMode.PREF_LAST_DISTANCE, distanceMeters)
                        .commit()
                }

                Log.d(TAG, "Post-window distance: ${distanceMeters.toInt()}m (threshold ${thresholdMeters.toInt()}m)")

                if (distanceMeters <= thresholdMeters) {
                    postStillHereNotification(mosqueName)
                } else {
                    Log.d(TAG, "Post-window: user already left mosque — no reminder needed")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Post-window coords check security exception", e)
        }
    }

    private fun postStillHereNotification(mosqueName: String) {
        ensureStillHereChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Persist the "card showing" state so ViewModel / UI can react
        prefs.edit()
            .putBoolean(PREF_SHOW_STAY_CARD, true)
            .putLong(PREF_STAY_CARD_POSTED_AT, System.currentTimeMillis())
            .apply()

        val dismissIntent = PendingIntent.getBroadcast(
            context, 7106,
            Intent(ACTION_STAY_HERE_DISMISS).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, STAY_HERE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🕌 Still at $mosqueName")
            .setContentText("You are still within the mosque. Please remember to keep your phone quiet manually if you are staying to read.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("You are still within the mosque. Please remember to keep your phone quiet manually if you are staying to read.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .addAction(NotificationCompat.Action(0, "✅ OK", dismissIntent))
            .build()

        nm.notify(STAY_HERE_NOTIF_ID, notif)
        Log.d(TAG, "Posted 'Still Here' reminder for $mosqueName")

        // Schedule auto-expiry so card doesn't linger indefinitely
        scheduleStayCardExpiry()
    }

    private fun ensureStillHereChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(STAY_HERE_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        STAY_HERE_CHANNEL_ID,
                        "Post-Prayer Mosque Reminder",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Reminder when still at the mosque after prayer window ends"
                        setSound(null, null)
                    }
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — NOTIFICATION CHANNEL HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureStayChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(STAY_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        STAY_CHANNEL_ID,
                        "In-Mosque Mode",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Notifications for In-Mosque quiet mode management"
                        setSound(null, null)
                    }
                )
            }
        }
    }

    private fun ensureDisabledChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(DISABLED_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DISABLED_CHANNEL_ID,
                        "Mosque Proximity Reminder",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Reminder to manually silence your phone near the mosque"
                        setSound(null, null)
                    }
                )
            }
        }
    }
}
