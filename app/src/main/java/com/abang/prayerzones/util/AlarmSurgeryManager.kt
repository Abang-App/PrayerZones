package com.abang.prayerzones.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.receiver.PrayerAlarmReceiver
import com.abang.prayerzones.repository.MosqueRepository
import com.abang.prayerzones.repository.PrayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs alarms for the currently active (selected) mosques.
 *
 * Goals:
 * - No global cancel-all.
 * - Schedule only 6 prayers per mosque.
 * - Idempotent: doesn't reschedule if already present (FLAG_NO_CREATE).
 */
@Singleton
class AlarmSurgeryManager @Inject constructor(
    private val mosqueRepository: MosqueRepository,
    private val prayerRepository: PrayerRepository,
    private val alarmScheduler: AlarmScheduler,
    private val appContext: Context
) {

    companion object {
        private const val TAG = "AlarmSurgery"
        private val PRAYER_ORDER = listOf("Fajr", "Duha", "Dhuhr", "Asr", "Maghrib", "Isha")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getAlarmId(slotIndex: Int, prayerIndex: Int): Int = (slotIndex * 10) + prayerIndex

    /**
     * Clears ALL existing prayer alarms for all 4 slots (0..3) and 6 prayers (0..5)
     * using the exact requestCode formula used in scheduling.
     *
     * This prevents "ghost" alarms from old mosque selections/time tables.
     */
    private fun cancelAllExistingAlarms() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            for (slotIndex in 0..3) {
                for (prayerIndex in 0..5) {
                    val requestCode = getAlarmId(slotIndex, prayerIndex)
                    val pi = PendingIntent.getBroadcast(
                        appContext,
                        requestCode,
                        Intent(appContext, PrayerAlarmReceiver::class.java).apply {
                            action = AlarmScheduler.ACTION_PRAYER_ALARM
                        },
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pi != null) {
                        alarmManager.cancel(pi)
                        pi.cancel()
                    }
                }
            }
            Log.d(TAG, "✅ cancelAllExistingAlarms: wipe complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ cancelAllExistingAlarms failed", e)
        }
    }

    fun installActiveAlarms() {
        scope.launch {
            try {
                // Master Check: if notifications disabled, cancel all and stop.
                val masterEnabled = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(appContext)
                    .getBoolean("pref_notifications_enabled", true)

                if (!masterEnabled) {
                    Log.w(TAG, "pref_notifications_enabled=false; wiping alarms and skipping scheduling")
                    cancelAllExistingAlarms()
                    return@launch
                }

                // Wipe first, then set.
                cancelAllExistingAlarms()

                val active = mosqueRepository.activeMosques.first()
                Log.d(TAG, "installActiveAlarms: active mosques=${active.size}")

                for ((index, mosque) in active.withIndex()) {
                    val isFirstMosque = index == 0
                    installMosqueAlarms(slotIndex = index, mosque = mosque, isFirstMosque = isFirstMosque)
                }

                Log.d(TAG, "installActiveAlarms: done")
            } catch (e: Exception) {
                Log.e(TAG, "installActiveAlarms failed", e)
            }
        }
    }

    private suspend fun installMosqueAlarms(slotIndex: Int, mosque: Mosque, isFirstMosque: Boolean) {
        // ─────────────────────────────────────────────────────────────────────────────────────────
        // REGRESSION 2 FIX: Strict TimeZone validation — never fall back to ZoneId.systemDefault().
        // A silent fallback causes alarms to fire at the device's local time, not the mosque's
        // prayer time, which means a mosque in Europe could fire at Singapore time. Instead, we
        // abort the entire mosque's scheduling if the zone is invalid. Use validZoneId (lazy
        // ZoneId.of() with null-safe) to stay consistent with the schedulePrayerAlarmIfMissing
        // overload that already enforces this rule.
        // ─────────────────────────────────────────────────────────────────────────────────────────
        val zoneId: ZoneId = mosque.validZoneId ?: run {
            Log.e(TAG, "🚫 ABORT scheduling for '${mosque.name}' (slot=$slotIndex): invalid/blank timeZone='${mosque.timeZone}'. Fix the mosque data to prevent phantom alarms.")
            // Cancel any previously-installed alarms for this slot so stale alarms don't remain.
            for (prayerIndex in 0..5) {
                alarmScheduler.cancelPrayerAlarmByRequestCode(getAlarmId(slotIndex, prayerIndex))
            }
            return
        }
        val now = mosque.getCurrentLocalTime()
        val today = now.toLocalDate()

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        val slot0MasterEnabled = prefs.getBoolean("pref_notifications_enabled", true)
        // If not present in XML yet, default ON.
        val secondaryMasterEnabled = prefs.getBoolean("pref_secondary_notifications_enabled", true)
        val globalMuteDuha = prefs.getBoolean("pref_mute_duha", false)

        // Slot-based master gating
        val slotEnabled: Boolean = if (slotIndex == 0) slot0MasterEnabled else secondaryMasterEnabled

        if (!slotEnabled) {
            Log.w(TAG, "Slot $slotIndex notifications disabled by prefs; cancelling its alarms")
            // Cancel all 6 prayer alarms for this slot.
            for (prayerIndex in 0..5) {
                alarmScheduler.cancelPrayerAlarmByRequestCode(getAlarmId(slotIndex, prayerIndex))
            }
            return
        }

        // ✅ Source-of-truth: prefer API timings if already cached (same as UI cache-first strategy)
        val timings: Map<String, String> = try {
            val cached = prayerRepository.getCachedPrayerTimes(mosque.id)
            if (cached != null) {
                Log.d(TAG, "Using cached API timings for scheduling: ${mosque.name}")
                PrayerFilter.filterToMap(cached.data.timings)
            } else {
                // No cache in memory: fetch network once to populate cache and schedule correctly.
                val response = prayerRepository.getPrayerTimes(mosque)
                Log.d(TAG, "Fetched API timings for scheduling: ${mosque.name}")
                PrayerFilter.filterToMap(response.data.timings)
            }
        } catch (e: Exception) {
            // Only fallback to defaults if both cache and API fetch fail.
            Log.w(TAG, "Falling back to default timings for ${mosque.name}: ${e.message}")
            val defaults = mosque.defaultTimings ?: emptyMap()
            PrayerFilter.filterToMap(defaults)
        }

        for ((prayerIndex, prayerName) in PRAYER_ORDER.withIndex()) {
            // Global Duha mute: cancel + skip.
            if (prayerName == "Duha" && globalMuteDuha) {
                Log.d(TAG, "Global Duha mute enabled; cancelling/suppressing Duha for slot=$slotIndex mosque=${mosque.name}")
                alarmScheduler.cancelPrayerAlarmByRequestCode(getAlarmId(slotIndex, prayerIndex))
                continue
            }

            // Per-mosque per-prayer mute from repository (bell icon persistence)
            val perPrayerMuted = runCatching { mosqueRepository.isPrayerMuted(mosque.id, prayerName) }.getOrDefault(false)
            if (perPrayerMuted) {
                Log.d(TAG, "Per-mosque prayer muted; cancelling/suppressing $prayerName for ${mosque.name} slot=$slotIndex")
                alarmScheduler.cancelPrayerAlarmByRequestCode(getAlarmId(slotIndex, prayerIndex))
                continue
            }

            val timeStr = timings[prayerName] ?: continue
            try {
                val time = LocalTime.parse(timeStr.take(5))
                var alarmTime = ZonedDateTime.of(today, time, zoneId)
                if (alarmTime.isBefore(now)) alarmTime = alarmTime.plusDays(1)

                Log.d(TAG, "Scheduling (if missing): ${mosque.name} $prayerName @ ${alarmTime.toLocalTime()} zone=$zoneId")

                alarmScheduler.schedulePrayerAlarmIfMissing(
                    mosque = mosque,
                    prayerName = prayerName,
                    prayerTime = alarmTime,
                    isFirstMosque = isFirstMosque,
                    requestCodeOverride = getAlarmId(slotIndex, prayerIndex)
                )

                // ── Geofence window (Slot 0 only, skip Duha) ────────────────
                // Schedule a T−Xmin activation alarm and T+Xmin deactivation alarm
                // so the geofence is only live during the prayer window.
                // NOTE: On Fridays, the API "Dhuhr" key still contains the correct
                // noon prayer time. Jumu'ah display is handled in the ViewModel UI only.
                // Duha is excluded because it's a secondary prayer (not a geofence trigger).
                if (slotIndex == 0 && prayerName != "Duha") {
                    runCatching {
                        alarmScheduler.scheduleGeofenceWindow(
                            mosque              = mosque,
                            prayerTimeUtcMillis = alarmTime.toInstant().toEpochMilli(),
                            prayerIndex         = prayerIndex
                        )
                    }.onFailure { Log.w(TAG, "Could not schedule geofence window for $prayerName", it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid time $timeStr for $prayerName (${mosque.name})", e)
            }
        }

        // After scheduling the 6 prayers for this slot, schedule a slot-specific midnight refresh.
        runCatching {
            alarmScheduler.scheduleMidnightRefresh(slotIndex, mosque.id, mosque.timeZone)
        }.onFailure {
            Log.e(TAG, "Failed to schedule midnight refresh for slot=$slotIndex mosque=${mosque.id}", it)
        }
    }
}
