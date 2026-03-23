package com.abang.prayerzones.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.abang.prayerzones.receiver.PrayerAlarmReceiver
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.Mosque
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "AlarmScheduler"
        private val TIME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Explicit action for our alarm broadcasts.
         * Useful for Logcat filtering and to protect against system delivering ambiguous intents.
         */
        const val ACTION_PRAYER_ALARM = "com.abang.prayerzones.ACTION_PRAYER_ALARM"
        const val ACTION_MIDNIGHT_REFRESH = "com.abang.prayerzones.ACTION_MIDNIGHT_REFRESH"

        const val EXTRA_SLOT_INDEX = "slot_index"

        /**
         * Keep this list aligned with what we actually schedule.
         * We intentionally exclude Sunrise/Sunset/etc.
         */
        private val PRAYER_ORDER: List<String> = listOf(
            "Fajr",
            "Duha",
            "Dhuhr",
            "Asr",
            "Maghrib",
            "Isha"
        )
    }

    /**
     * Rule 1: unique request code per mosque + prayer index.
     */
    private fun requestCodeFor(mosqueId: String, prayerName: String): Int {
        val prayerIndex = PRAYER_ORDER.indexOf(prayerName).takeIf { it >= 0 } ?: 99
        return kotlin.math.abs(mosqueId.hashCode() + prayerIndex)
    }

    /**
     * Rule 2: only cancels alarms for a single removed mosque.
     */
    fun cancelAlarmsForMosque(mosqueId: String) {
        try {
            PRAYER_ORDER.forEach { prayerName ->
                cancelPrayerAlarm(prayerName, mosqueId)
            }
            Log.d(TAG, "✅ Cancelled alarms for mosque: $mosqueId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cancelling alarms for mosque $mosqueId", e)
        }
    }

    /**
     * Backward-compatible name (existing callers). Delegates to the surgical function.
     */
    fun cancelAllAlarmsForMosque(mosqueId: String) {
        cancelAlarmsForMosque(mosqueId)
    }

    /**
     * Checks whether a specific alarm PendingIntent already exists (no creation).
     */
    private fun isAlarmSet(prayerName: String, mosqueId: String, requestCodeOverride: Int? = null): Boolean {
        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_PRAYER_ALARM
            putExtra(PrayerAlarmReceiver.EXTRA_PRAYER_NAME, prayerName)
        }

        val requestCode = requestCodeOverride ?: requestCodeFor(mosqueId, prayerName)

        val existing = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        return existing != null
    }

    /**
     * Rule 2: do NOT cancel inside scheduling. Only schedule if missing.
     */
    fun schedulePrayerAlarmIfMissing(
        prayerName: String,
        prayerTime: ZonedDateTime,
        mosqueName: String,
        isFirstMosque: Boolean,
        mosqueId: String,
        requestCodeOverride: Int? = null
    ) {
        // Safety check: Never schedule excluded prayers
        if (PrayerFilter.isExcluded(prayerName)) {
            Log.w(TAG, "REFUSING to schedule alarm for excluded prayer: $prayerName")
            return
        }

        // ✅ FIX: Cancel uses the SAME requestCode that was (or will be) used to schedule.
        // Previously cancelPrayerAlarm() used requestCodeFor() (hash-based) while the alarm
        // was scheduled with requestCodeOverride (slot*10+prayerIndex). This mismatch left
        // the original alarm alive, creating two alarms for the same prayer → double-fire.
        if (isAlarmSet(prayerName, mosqueId, requestCodeOverride)) {
            val codeUsed = requestCodeOverride ?: requestCodeFor(mosqueId, prayerName)
            Log.w(TAG, "⚠️ Zombie alarm for $prayerName mosque=$mosqueId (rc=$codeUsed) — cancelling before reschedule")
            cancelPrayerAlarmByRequestCode(codeUsed)
        }

        // Always schedule with fresh UTC epoch calculation
        schedulePrayerAlarm(prayerName, prayerTime, mosqueName, isFirstMosque, mosqueId, requestCodeOverride)
    }

    /**
     * Strict-mode helper: validates mosque.timeZone via mosque.validZoneId before scheduling.
     * This prevents phantom alarms that would otherwise use the device zone.
     */
    fun schedulePrayerAlarmIfMissing(
        mosque: Mosque,
        prayerName: String,
        prayerTime: ZonedDateTime,
        isFirstMosque: Boolean,
        requestCodeOverride: Int? = null
    ) {
        if (mosque.validZoneId == null) {
            Log.e(TAG, "🚫 Refusing to schedule alarms for '${mosque.name}' because timeZone is invalid: '${mosque.timeZone}'")
            return
        }

        schedulePrayerAlarmIfMissing(
            prayerName = prayerName,
            prayerTime = prayerTime,
            mosqueName = mosque.name,
            isFirstMosque = isFirstMosque,
            mosqueId = mosque.id,
            requestCodeOverride = requestCodeOverride
        )
    }

    fun schedulePrayerAlarm(
        prayerName: String,
        prayerTime: ZonedDateTime,
        mosqueName: String,
        isFirstMosque: Boolean,
        mosqueId: String,
        requestCodeOverride: Int? = null
    ) {
        // Safety check: Never schedule excluded prayers
        if (PrayerFilter.isExcluded(prayerName)) {
            Log.w(TAG, "REFUSING to schedule alarm for excluded prayer: $prayerName")
            return
        }

        try {
            // ✅ Convert to UTC epoch milliseconds
            val utcEpochMillis = prayerTime.toInstant().toEpochMilli()
            val phoneZone = java.time.ZoneId.systemDefault()
            val phoneTime = prayerTime.withZoneSameInstant(phoneZone)

            val requestCode = requestCodeOverride ?: requestCodeFor(mosqueId, prayerName)

            // ✅ Enhanced logging for timezone verification
            Log.d(TAG, """
                ══════════════════════════════════════════════════════════════
                ALARM SCHEDULER - Scheduling Alarm
                ──────────────────────────────────────────────────────────────
                Mosque: $mosqueName (ID: $mosqueId)
                Prayer: $prayerName
                ──────────────────────────────────────────────────────────────
                Mosque Timezone: ${prayerTime.zone}
                Mosque Prayer Time: ${prayerTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}
                ──────────────────────────────────────────────────────────────
                UTC Epoch Millis: $utcEpochMillis
                ──────────────────────────────────────────────────────────────
                Phone Timezone: $phoneZone
                Phone Local Time: ${phoneTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}
                ──────────────────────────────────────────────────────────────
                Audio Mode: ${if (isFirstMosque) "FULL AZAN" else "NOTIFICATION TONE"}
                Request Code: $requestCode
                ══════════════════════════════════════════════════════════════
            """.trimIndent())

            val pendingIntent = createPendingIntent(prayerName, prayerTime, mosqueName, isFirstMosque, mosqueId, requestCode)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    scheduleExactAlarm(utcEpochMillis, pendingIntent)
                    Log.d(TAG, "✅ Exact alarm scheduled for $prayerName")
                } else {
                    scheduleInexactAlarm(utcEpochMillis, pendingIntent)
                    Log.w(TAG, "⚠️ Inexact alarm scheduled for $prayerName (exact alarm permission not granted)")
                }
            } else {
                scheduleExactAlarm(utcEpochMillis, pendingIntent)
                Log.d(TAG, "✅ Exact alarm scheduled for $prayerName (pre-Android S)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling alarm for $prayerName at $mosqueName", e)
        }
    }

    fun cancelPrayerAlarm(prayerName: String, mosqueId: String) {
        try {
            val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
                action = ACTION_PRAYER_ALARM
                putExtra(PrayerAlarmReceiver.EXTRA_PRAYER_NAME, prayerName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCodeFor(mosqueId, prayerName),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            Log.d(TAG, "Cancelled alarm for $prayerName at mosque $mosqueId")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling alarm for $prayerName", e)
        }
    }

    /**
     * Cancels an alarm by the explicit requestCode used to schedule it.
     * This is used by AlarmSurgeryManager for slotIndex-based IDs (slot*10+prayerIndex).
     */
    fun cancelPrayerAlarmByRequestCode(requestCode: Int) {
        try {
            val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
                action = ACTION_PRAYER_ALARM
            }
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
                Log.d(TAG, "Cancelled alarm by requestCode=$requestCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling alarm by requestCode=$requestCode", e)
        }
    }

    @Deprecated(
        message = "Nuclear cancellation removed. Cancel alarms only per mosque.",
        level = DeprecationLevel.WARNING
    )
    fun cancelAllAlarms(@Suppress("UNUSED_PARAMETER") mosquesIds: List<String>) {
        // Intentionally no-op to prevent nuking alarms on startup.
        Log.w(TAG, "⚠️ cancelAllAlarms() is deprecated and no-op. Use cancelAlarmsForMosque().")
    }

    private fun createPendingIntent(
        prayerName: String,
        prayerTime: ZonedDateTime,
        mosqueName: String,
        isFirstMosque: Boolean,
        mosqueId: String,
        requestCode: Int
    ): PendingIntent {
        // ✅ FIX: Calculate target UTC epoch millis for validation in receiver
        val targetUtcMillis = prayerTime.toInstant().toEpochMilli()

        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_PRAYER_ALARM
            putExtra(PrayerAlarmReceiver.EXTRA_PRAYER_NAME, prayerName)
            putExtra(PrayerAlarmReceiver.EXTRA_PRAYER_TIME, prayerTime.format(TIME_FORMATTER))
            putExtra(PrayerAlarmReceiver.EXTRA_MOSQUE_NAME, mosqueName)
            putExtra(PrayerAlarmReceiver.EXTRA_IS_FIRST_MOSQUE, isFirstMosque)
            putExtra(PrayerAlarmReceiver.EXTRA_MOSQUE_ID, mosqueId)
            putExtra(
                PrayerAlarmReceiver.EXTRA_MOSQUE_TIMEZONE,
                prayerTime.zone?.id?.takeIf { it.isNotBlank() }
                    ?: java.time.ZoneId.systemDefault().id.also {
                        Log.e(TAG, "⚠️ FALLBACK: prayerTime.zone is null/blank for $prayerName at $mosqueName — using device systemDefault. THIS IS A BUG; fix mosque.timeZone.")
                    }
            )
            // ✅ NEW: Add target timestamp for ghost alarm detection
            putExtra(com.abang.prayerzones.receiver.PrayerAlarmReceiver.EXTRA_TARGET_MILLIS, targetUtcMillis)
            // Provide slot index if the caller used requestCodeOverride scheme (slot*10+prayerIndex)
            val inferredSlot = requestCode / 10
            if (inferredSlot in 0..3) {
                putExtra(EXTRA_SLOT_INDEX, inferredSlot)
            }
        }

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExactAlarm(utcEpochMillis: Long, pendingIntent: PendingIntent) {
        // ✅ FIX: Samsung/Xiaomi safety check - never schedule alarms in the past
        val nowMillis = System.currentTimeMillis()
        if (utcEpochMillis < nowMillis) {
            val delayMinutes = (nowMillis - utcEpochMillis) / 60000
            Log.e(TAG, "🚨 BLOCKED: Attempted to schedule alarm $delayMinutes minutes in the PAST. Skipping to prevent ghost alarms.")
            return
        }

        // Log time until alarm for verification
        val minutesUntilAlarm = (utcEpochMillis - nowMillis) / 60000
        Log.d(TAG, "⏰ Scheduling alarm to fire in $minutesUntilAlarm minutes (${utcEpochMillis - nowMillis}ms)")

        // ✅ FIX #2: Use setAlarmClock() instead of setExactAndAllowWhileIdle()
        // This is the ONLY way to achieve 100% precision on Samsung Android 11
        // and bypass "not align" errors in LogCat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Create a PendingIntent that opens the app when the user taps the alarm icon
            val showIntent = Intent(context, com.abang.prayerzones.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                0,
                showIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val alarmClockInfo = AlarmManager.AlarmClockInfo(utcEpochMillis, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "✅ Using setAlarmClock() for maximum precision")
        } else {
            // Fallback for API < 21 (shouldn't happen with minSdk 30)
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                utcEpochMillis,
                pendingIntent
            )
        }
    }

    private fun scheduleInexactAlarm(utcEpochMillis: Long, pendingIntent: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            utcEpochMillis,
            pendingIntent
        )
    }

    fun scheduleMidnightRefresh(slotIndex: Int, mosqueId: String, mosqueTimeZone: String) {
        if (slotIndex !in 0..3) return

        val zoneId = runCatching { ZoneId.of(mosqueTimeZone) }.getOrElse { ZoneId.systemDefault() }
        val now = ZonedDateTime.now(zoneId)
        val nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zoneId)

        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = ACTION_MIDNIGHT_REFRESH
            putExtra(EXTRA_SLOT_INDEX, slotIndex)
            putExtra(PrayerAlarmReceiver.EXTRA_MOSQUE_ID, mosqueId)
            putExtra(PrayerAlarmReceiver.EXTRA_MOSQUE_TIMEZONE, zoneId.id)
        }

        val requestCode = (slotIndex * 100) + 99
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use alarm clock for maximum reliability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextMidnight.toInstant().toEpochMilli(), pi),
                pi
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextMidnight.toInstant().toEpochMilli(), pi)
        }


        Log.d(TAG, "Scheduled midnight refresh for slot=$slotIndex mosque=$mosqueId at ${nextMidnight}")
    }

    /**
     * Schedule a prayer window for In-Mosque Mode enforcement.
     *
     * Two inexact alarms are set:
     *  - (prayerTime − pref_near_prayer_time): ACTION_WINDOW_START
     *  - (prayerTime + pref_near_prayer_time): ACTION_WINDOW_END
     *
     * The window width is read from SharedPreferences via PrefDefaults (resource-based default).
     * Window-based enforcement means mandatory restoration at window end, regardless of location.
     */
    fun scheduleGeofenceWindow(
        mosque: com.abang.prayerzones.model.Mosque,
        prayerTimeUtcMillis: Long,
        prayerIndex: Int = 0
    ) {
        // Debug mode uses 2-minute window; production reads pref_near_prayer_time
        val isDebug = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("DEBUG_MODE_ENABLED", false)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val prefMinutes = prefs.getString("pref_near_prayer_time", InMosqueMode.DEFAULT_NEAR_PRAYER_MIN)!!.toLong()
        val windowMs = if (isDebug) 2 * 60_000L else prefMinutes * 60_000L

        val openMs  = prayerTimeUtcMillis - windowMs
        val closeMs = prayerTimeUtcMillis + windowMs
        val nowMs   = System.currentTimeMillis()

        Log.d(TAG, "In-Mosque window for ${mosque.name} prayer#$prayerIndex: ${windowMs / 60_000}min (debug=$isDebug)")

        // ── FIX 3: Reset PREF_ACTIVE between prayers ──
        // Ensures every prayer window starts "Orange" and only turns "Green"
        // after a fresh location check confirms the user is at the mosque.
        prefs.edit().putBoolean(InMosqueMode.PREF_ACTIVE, false).apply()

        // Unique request code per mosque AND per prayer.
        // Use modulo to keep the hash manageable, multiply by 10 to give room for
        // START/END variants, and add the prayerIndex so different prayers never collide.
        val mosqueHash = (mosque.id.hashCode() and 0x7FFFFFFF) % 10000  // 0..9999
        val baseCode   = 80000 + mosqueHash * 10 + prayerIndex          // unique per mosque+prayer
        val startCode  = baseCode * 2       // even → START
        val endCode    = baseCode * 2 + 1   // odd  → END

        fun makePi(action: String, code: Int): PendingIntent {
            val intent = Intent(context, com.abang.prayerzones.receiver.InMosqueModeReceiver::class.java).apply {
                this.action = action
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_ID,         mosque.id)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_NAME,       mosque.name)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_LAT,        mosque.latitude)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_LNG,        mosque.longitude)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_PRAYER_TIME_MS,    prayerTimeUtcMillis)
                // Add window end time for enforcement
                putExtra(com.abang.prayerzones.util.InMosqueModeManager.EXTRA_WINDOW_END_TIME,        closeMs)
            }
            return PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        if (openMs > nowMs) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, openMs,
                makePi(com.abang.prayerzones.receiver.InMosqueModeReceiver.ACTION_WINDOW_START, startCode)
            )
            Log.d(TAG, "Window START alarm (exact): ${mosque.name} prayer#$prayerIndex at ${java.util.Date(openMs)} reqCode=$startCode")
        } else if (nowMs < closeMs) {
            // ── CATCH-UP: window is already open but START alarm was missed ──
            // Fire the same broadcast the alarm would have sent so the Receiver
            // handles it identically (respects DI singleton of InMosqueModeManager).
            Log.w(TAG, "⚡ CATCH-UP: Window already open for ${mosque.name} prayer#$prayerIndex " +
                    "(opened ${(nowMs - openMs) / 60_000}min ago). Sending immediate WINDOW_START broadcast.")
            val catchUpIntent = Intent(context, com.abang.prayerzones.receiver.InMosqueModeReceiver::class.java).apply {
                action = com.abang.prayerzones.receiver.InMosqueModeReceiver.ACTION_WINDOW_START
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_ID,      mosque.id)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_NAME,    mosque.name)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_LAT,     mosque.latitude)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_MOSQUE_LNG,     mosque.longitude)
                putExtra(com.abang.prayerzones.receiver.InMosqueModeReceiver.EXTRA_PRAYER_TIME_MS, prayerTimeUtcMillis)
                putExtra(InMosqueModeManager.EXTRA_WINDOW_END_TIME,                                closeMs)
            }
            context.sendBroadcast(catchUpIntent)
        }

        if (closeMs > nowMs) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, closeMs,
                makePi(com.abang.prayerzones.receiver.InMosqueModeReceiver.ACTION_WINDOW_END, endCode)
            )
            Log.d(TAG, "Window END alarm (exact): ${mosque.name} prayer#$prayerIndex at ${java.util.Date(closeMs)} reqCode=$endCode")
        }
    }
}

