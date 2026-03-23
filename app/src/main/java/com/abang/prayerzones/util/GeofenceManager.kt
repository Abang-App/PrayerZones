package com.abang.prayerzones.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.receiver.InMosqueModeReceiver
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GeofenceManager
 *
 * Manages geofencing for the "Auto In-Mosque" feature.
 *
 * - Primary:  Registers a Geofence with Google's GeofencingClient (radius from pref_min_distance_mosque).
 *   The enter/exit transitions are delivered to [InMosqueModeReceiver] via BroadcastReceiver.
 *
 * - Bug #3 fix: After geofence registration, immediately requests a single location update.
 *   If the user is already within [pref_min_distance_mosque] metres of the mosque,
 *   [InMosqueModeManager.onEnterMosque] is called directly — bypassing the ENTER transition
 *   that would never fire because the user was already inside.
 *
 * - Fallback: If Geofencing fails (or ACCESS_BACKGROUND_LOCATION is not granted),
 *   falls back to a FusedLocation foreground poll (every 60 s, 100 m displacement).
 *   The fallback is active ONLY when the app is in the foreground; it is stopped
 *   in onStop() of the Activity lifecycle.
 *
 * - Dynamic updates: when the nearest mosque changes, removeGeofence() is called
 *   for the old mosque and addGeofence() for the new one.
 */
@Singleton
class GeofenceManager @Inject constructor(
    private val context: Context,
    private val inMosqueModeManager: InMosqueModeManager
) {

    companion object {
        private const val TAG                = "GeofenceManager"
        private const val GEOFENCE_ID_PREFIX = "pz_mosque_"
    }

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Tracks which mosque id is currently registered as geofence
    private var activeGeofenceMosqueId: String? = null
    private var fallbackLocationCallback: LocationCallback? = null

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Register (or update) a geofence for [mosque].
     * Safe to call multiple times — removes any previously registered geofence first.
     *
     * [prayerTimeUtcMillis] is forwarded to [InMosqueModeManager.onEnterMosque] if the user
     * is already inside the geofence radius when this is called (Bug #3 fix).
     */
    @SuppressLint("MissingPermission")
    fun addGeofence(mosque: Mosque, prayerTimeUtcMillis: Long = 0L, windowEndUtcMillis: Long = 0L) {
        // NOTE: geofence is registered regardless of toggle state.
        // If mode is DISABLED, onEnterMosque() will fire a manual reminder instead
        // of changing the ringer. This is intentional — see InMosqueModeManager.
        if (!hasFineLocation()) {
            Log.w(TAG, "No fine location permission — cannot register geofence")
            return
        }

        // Remove previous geofence if it's a different mosque
        if (activeGeofenceMosqueId != null && activeGeofenceMosqueId != mosque.id) {
            removeGeofence(activeGeofenceMosqueId!!)
        }

        // Read radius from pref_min_distance_mosque
        val geofenceRadius = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()

        val geofenceId = GEOFENCE_ID_PREFIX + mosque.id

        // Calculate expiration: geofence dies exactly at window end (no buffer).
        // If no windowEndUtcMillis provided, expire after 2 hours as a fallback.
        val expirationDuration = if (windowEndUtcMillis > System.currentTimeMillis()) {
            (windowEndUtcMillis - System.currentTimeMillis())
        } else {
            2 * 60 * 60_000L // 2-hour fallback
        }

        val geofence = Geofence.Builder()
            .setRequestId(geofenceId)
            .setCircularRegion(mosque.latitude, mosque.longitude, geofenceRadius)
            .setExpirationDuration(expirationDuration)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setLoiteringDelay(60_000) // 1 minute dwell before ENTER fires
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pi = geofencePendingIntent(mosque.id, mosque.name, prayerTimeUtcMillis)

        geofencingClient.addGeofences(request, pi)
            .addOnSuccessListener {
                activeGeofenceMosqueId = mosque.id
                Log.d(TAG, "✅ Geofence added for ${mosque.name} (${mosque.id}) radius=${geofenceRadius.toInt()}m expires in ${expirationDuration / 60_000}min")
                // Bug #3 fix: check if user is already inside right now
                checkIfAlreadyInsideMosque(mosque, prayerTimeUtcMillis)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "⚠️ Geofence failed for ${mosque.name}: ${e.message}. Starting fallback.")
                startFallbackLocationPoll(mosque, prayerTimeUtcMillis)
            }
    }

    /** Remove an active geofence by mosqueId. */
    fun removeGeofence(mosqueId: String) {
        val geofenceId = GEOFENCE_ID_PREFIX + mosqueId
        geofencingClient.removeGeofences(listOf(geofenceId))
            .addOnSuccessListener {
                Log.d(TAG, "Geofence removed for $mosqueId")
                if (activeGeofenceMosqueId == mosqueId) activeGeofenceMosqueId = null
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to remove geofence for $mosqueId: ${e.message}")
            }
    }

    /** Stop the fallback location poll (call from Activity.onStop). */
    fun stopFallbackPoll() {
        fallbackLocationCallback?.let {
            fusedClient.removeLocationUpdates(it)
            fallbackLocationCallback = null
            Log.d(TAG, "Fallback location poll stopped")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — BUG #3 FIX: IMMEDIATE "ALREADY INSIDE" CHECK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * After the geofence is registered, request the user's current location once.
     * If they are already within [pref_min_distance_mosque] metres of the mosque,
     * call [InMosqueModeManager.onEnterMosque] directly without waiting for the ENTER
     * geofence transition (which never fires when you're already inside).
     */
    @SuppressLint("MissingPermission")
    private fun checkIfAlreadyInsideMosque(mosque: Mosque, prayerTimeUtcMillis: Long) {
        if (!hasFineLocation()) return
        // Skip if already active to avoid re-triggering
        if (inMosqueModeManager.isActive()) return

        val thresholdMetres = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()

        val locationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(30_000L) // accept a location up to 30s old
            .build()

        fusedClient.getCurrentLocation(locationRequest, null)
            .addOnSuccessListener { location: Location? ->
                if (location == null) {
                    Log.d(TAG, "Already-inside check: no location available")
                    return@addOnSuccessListener
                }
                val distanceM = distanceBetween(
                    location.latitude, location.longitude,
                    mosque.latitude, mosque.longitude
                )
                Log.d(TAG, "Already-inside check: dist=${distanceM.toInt()}m threshold=${thresholdMetres.toInt()}m")

                // Only persist distance if the feature toggle is ON.
                // Prevents "ghost writes" that leave stale values when the feature is disabled.
                val modeEnabled = InMosqueMode.fromPref(
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                ) != InMosqueMode.DISABLED
                if (modeEnabled) {
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putFloat(InMosqueMode.PREF_LAST_DISTANCE, distanceM)
                        .apply()
                }

                if (distanceM <= thresholdMetres) {
                    // Only trigger onWindowStart if we are actually within a prayer window.
                    // If the user just toggled the feature ON at the mosque outside prayer time,
                    // we should NOT silence their phone — just stay Orange (standby).
                    val withinWindow = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(InMosqueModeManager.PREF_WITHIN_WINDOW, false)
                    if (withinWindow) {
                        Log.i(TAG, "📍 User already inside ${mosque.name} AND within prayer window — triggering onWindowStart directly")
                        val windowEnd = PreferenceManager.getDefaultSharedPreferences(context)
                            .getLong(InMosqueModeManager.EXTRA_WINDOW_END_TIME, 0L)
                        inMosqueModeManager.onWindowStart(
                            mosque.name, mosque.id,
                            mosque.latitude, mosque.longitude,
                            windowEnd
                        )
                    } else {
                        Log.d(TAG, "📍 User inside ${mosque.name} but OUTSIDE prayer window — geofence registered, waiting for window to open")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Already-inside check failed: ${e.message}")
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — PENDING INTENT FOR GEOFENCE TRANSITIONS
    // ─────────────────────────────────────────────────────────────────────────

    private fun geofencePendingIntent(
        mosqueId: String,
        mosqueName: String,
        prayerTimeUtcMillis: Long
    ): PendingIntent {
        val intent = Intent(context, InMosqueModeReceiver::class.java).apply {
            action = InMosqueModeReceiver.ACTION_GEOFENCE_TRANSITION
            putExtra(InMosqueModeReceiver.EXTRA_MOSQUE_ID,      mosqueId)
            putExtra(InMosqueModeReceiver.EXTRA_MOSQUE_NAME,    mosqueName)
            putExtra(InMosqueModeReceiver.EXTRA_PRAYER_TIME_MS, prayerTimeUtcMillis)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        return PendingIntent.getBroadcast(
            context,
            (mosqueId.hashCode() and 0x7FFF),
            intent,
            flags
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — FALLBACK FOREGROUND LOCATION POLL
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startFallbackLocationPoll(mosque: Mosque, prayerTimeUtcMillis: Long) {
        if (!hasFineLocation()) return
        if (fallbackLocationCallback != null) return // already polling

        fallbackLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val distanceM = distanceBetween(
                    loc.latitude, loc.longitude,
                    mosque.latitude, mosque.longitude
                )
                // Read pref each poll so changes take effect immediately
                val radius = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()

                // Only persist distance if the feature toggle is ON.
                val modeEnabled = InMosqueMode.fromPref(
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                ) != InMosqueMode.DISABLED
                if (modeEnabled) {
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putFloat(InMosqueMode.PREF_LAST_DISTANCE, distanceM)
                        .apply()
                }

                val isActive = inMosqueModeManager.isActive()
                // Only respond to proximity changes while inside the prayer window
                val withinWindow = PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(InMosqueModeManager.PREF_WITHIN_WINDOW, false)

                if (!withinWindow) {
                    Log.d(TAG, "Fallback: outside prayer window — ignoring location update")
                    return
                }

                if (distanceM <= radius && !isActive) {
                    Log.d(TAG, "Fallback: entered geofence for ${mosque.name} (dist=${distanceM.toInt()}m radius=${radius.toInt()}m)")
                    val windowEnd = PreferenceManager.getDefaultSharedPreferences(context)
                        .getLong(InMosqueModeManager.EXTRA_WINDOW_END_TIME, 0L)
                    inMosqueModeManager.onWindowStart(
                        mosque.name, mosque.id,
                        mosque.latitude, mosque.longitude,
                        windowEnd
                    )
                } else if (distanceM > radius * 1.5f && isActive) {
                    Log.d(TAG, "Fallback: exited geofence for ${mosque.name}")
                    inMosqueModeManager.onWindowEnd()
                }
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L
        )
            .setMinUpdateDistanceMeters(10f)
            .build()

        fusedClient.requestLocationUpdates(
            locationRequest,
            fallbackLocationCallback!!,
            Looper.getMainLooper()
        )
        Log.d(TAG, "Fallback location poll started for ${mosque.name}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL — HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }
}

