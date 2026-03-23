package com.abang.prayerzones.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.util.GeofenceManager
import com.abang.prayerzones.util.InMosqueModeManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * InMosqueModeReceiver
 *
 * Handles these event types:
 *
 * 1. ACTION_WINDOW_START          — Prayer window opened (T−NearPrayerTime)
 * 2. ACTION_WINDOW_END            — Prayer window closed (T+NearPrayerTime)
 * 3. ACTION_GEOFENCE_TRANSITION   — Geofence enter/exit from GeofencingClient
 * 4. ACTION_STAY_KEEP             — "Keep ON" button from the stay notification
 * 5. ACTION_STAY_EXIT             — "Cancel" button from the stay notification
 * 6. ACTION_DISABLED_DISMISS      — "OK" on the DISABLED proximity reminder
 * 7. ACTION_STAY_HERE_DISMISS     — "OK" on the post-window "Still Here" reminder
 */
@AndroidEntryPoint
class InMosqueModeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InMosqueModeReceiver"

        const val ACTION_GEOFENCE_TRANSITION  = "com.abang.prayerzones.ACTION_GEOFENCE_TRANSITION"
        const val ACTION_STAY_KEEP            = "com.abang.prayerzones.IN_MOSQUE_STAY_KEEP"
        const val ACTION_STAY_EXIT            = "com.abang.prayerzones.IN_MOSQUE_STAY_EXIT"

        // Window-based enforcement actions
        const val ACTION_WINDOW_START         = "com.abang.prayerzones.ACTION_WINDOW_START"
        const val ACTION_WINDOW_END           = "com.abang.prayerzones.ACTION_WINDOW_END"

        const val EXTRA_MOSQUE_ID       = "mosque_id"
        const val EXTRA_MOSQUE_NAME     = "mosque_name"
        const val EXTRA_MOSQUE_LAT      = "mosque_lat"
        const val EXTRA_MOSQUE_LNG      = "mosque_lng"
        /** UTC epoch millis of the scheduled prayer — used to anchor the stay timer. */
        const val EXTRA_PRAYER_TIME_MS  = "prayer_time_ms"
    }

    @Inject lateinit var inMosqueModeManager: InMosqueModeManager
    @Inject lateinit var geofenceManager: GeofenceManager

    /**
     * Acquire a CPU [PowerManager.WakeLock] with a 15-second auto-release timeout.
     * Guarantees the CPU stays awake long enough for AudioManager to silence the
     * ringer, for the Toast to display, and for the high-accuracy location check
     * to complete — even when the device has been in Doze for hours.
     */
    private fun acquireWakeLock(context: Context, tag: String): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrayerZones:$tag").apply {
            acquire(15_000L)   // auto-release after 15 s
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            // ── Window START: Prayer window opened (T-NearPrayerTime) ─────
            ACTION_WINDOW_START -> {
                val wakeLock = acquireWakeLock(context, "WindowStart")
                try {
                    val mosqueId        = intent.getStringExtra(EXTRA_MOSQUE_ID)   ?: return
                    val mosqueName      = intent.getStringExtra(EXTRA_MOSQUE_NAME) ?: "Mosque"
                    val lat             = intent.getDoubleExtra(EXTRA_MOSQUE_LAT, 0.0)
                    val lng             = intent.getDoubleExtra(EXTRA_MOSQUE_LNG, 0.0)
                    val prayerTimeUtcMs = intent.getLongExtra(EXTRA_PRAYER_TIME_MS, 0L)

                    Log.i(TAG, "⏰ WINDOW START for $mosqueName — WakeLock acquired, performing location check")

                    // Calculate window end time (prayer time + near_prayer_time)
                    val nearMinutes = PreferenceManager.getDefaultSharedPreferences(context)
                        .getString("pref_near_prayer_time", InMosqueMode.DEFAULT_NEAR_PRAYER_MIN)!!.toInt()
                    val windowEndTimeMs = prayerTimeUtcMs + (nearMinutes * 60 * 1000L)

                    inMosqueModeManager.onWindowStart(
                        mosqueName, mosqueId, lat, lng, windowEndTimeMs
                    )
                } finally {
                    // The WakeLock has a 15 s timeout, but release eagerly if the
                    // synchronous part finished sooner. Async location callbacks
                    // are covered by the timeout.
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }

            // ── Window END: Prayer window closed (T+NearPrayerTime) ────────
            ACTION_WINDOW_END -> {
                val wakeLock = acquireWakeLock(context, "WindowEnd")
                try {
                    val lat = intent.getDoubleExtra(EXTRA_MOSQUE_LAT, 0.0)
                    val lng = intent.getDoubleExtra(EXTRA_MOSQUE_LNG, 0.0)
                    Log.i(TAG, "⏰ WINDOW END — WakeLock acquired, mandatory restoration (coords=$lat,$lng)")
                    if (lat != 0.0 || lng != 0.0) {
                        inMosqueModeManager.onWindowEndWithCoords(lat, lng)
                    } else {
                        inMosqueModeManager.onWindowEnd()
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }

            // ── Geofence transition from GeofencingClient ─────────────────
            ACTION_GEOFENCE_TRANSITION -> {
                val mosqueId         = intent.getStringExtra(EXTRA_MOSQUE_ID)       ?: ""
                val mosqueName       = intent.getStringExtra(EXTRA_MOSQUE_NAME)     ?: "Mosque"
                val prayerTimeUtcMs  = intent.getLongExtra(EXTRA_PRAYER_TIME_MS, 0L)

                @Suppress("DEPRECATION")
                val event = GeofencingEvent.fromIntent(intent)
                if (event == null || event.hasError()) {
                    Log.e(TAG, "GeofencingEvent error: ${event?.errorCode}")
                    return
                }

                // Only respond to geofence transitions while inside the prayer window
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val withinWindow = prefs.getBoolean(InMosqueModeManager.PREF_WITHIN_WINDOW, false)
                if (!withinWindow) {
                    Log.d(TAG, "Geofence transition ignored — outside prayer window")
                    return
                }

                when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        Log.i(TAG, "📍 ENTER geofence: $mosqueName ($mosqueId)")
                        val windowEnd = prefs.getLong(InMosqueModeManager.EXTRA_WINDOW_END_TIME, 0L)
                        val lat = prefs.getFloat("pref_last_mosque_lat", 0f).toDouble()
                        val lng = prefs.getFloat("pref_last_mosque_lng", 0f).toDouble()
                        inMosqueModeManager.onWindowStart(
                            mosqueName, mosqueId, lat, lng, windowEnd
                        )
                    }
                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        Log.i(TAG, "📍 EXIT geofence: $mosqueName ($mosqueId)")
                        // Do NOT call onWindowEnd here — the timer-based alarm handles that.
                        // Only log the exit for diagnostics.
                        Log.d(TAG, "EXIT transition noted — window-end alarm will handle restoration")
                    }
                    else -> Log.d(TAG, "Ignoring geofence transition: ${event.geofenceTransition}")
                }
            }


            // ── Stay-timer "Keep ON" button ───────────────────────────────
            ACTION_STAY_KEEP -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val mosqueName = prefs.getString(InMosqueMode.PREF_MOSQUE_NAME, "Mosque") ?: "Mosque"
                val mosqueId = prefs.getString(InMosqueMode.PREF_MOSQUE_ID, "") ?: ""
                val lat = prefs.getFloat("pref_last_mosque_lat", 0f).toDouble()
                val lng = prefs.getFloat("pref_last_mosque_lng", 0f).toDouble()
                Log.d(TAG, "Stay: user chose KEEP ON for $mosqueName")
                // Re-arm with a 30-minute fallback window
                val windowEnd = System.currentTimeMillis() + 30 * 60_000L
                inMosqueModeManager.onWindowStart(mosqueName, mosqueId, lat, lng, windowEnd)
            }

            // ── Stay-timer "Cancel" ex Turn Off button ──────────────────────────────
            ACTION_STAY_EXIT -> {
                Log.d(TAG, "Stay: user chose Cancel")
                inMosqueModeManager.onWindowEnd()
            }

            // ── "OK" on the DISABLED proximity reminder ───────────────────
            InMosqueModeManager.ACTION_DISABLED_DISMISS -> {
                Log.d(TAG, "Disabled reminder dismissed by user")
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm?.cancel(7103)
            }

            // ── "OK" on the post-window "Still Here" reminder ──────────────
            InMosqueModeManager.ACTION_STAY_HERE_DISMISS -> {
                Log.d(TAG, "Still-here reminder dismissed by user")
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm?.cancel(7105)
                // Clear the persisted flag so ViewModel / UI stop showing the card
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean(InMosqueModeManager.PREF_SHOW_STAY_CARD, false)
                    .apply()
            }

            else -> Log.d(TAG, "Unhandled action: ${intent.action}")
        }
    }
}
