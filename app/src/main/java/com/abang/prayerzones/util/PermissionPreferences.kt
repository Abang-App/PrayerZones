package com.abang.prayerzones.util

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "permission_preferences",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_NOTIFICATION_DISMISSED_TIME = "notification_dismissed_time"
        private const val KEY_EXACT_ALARM_DISMISSED_TIME = "exact_alarm_dismissed_time"
        private const val KEY_BATTERY_OPTIMIZATION_DISMISSED_TIME = "battery_optimization_dismissed_time"

        private const val KEY_PERMISSIONS_CHECKED_THIS_SESSION = "permissions_checked_this_session"

        // 24 hours in milliseconds
        private const val DISMISS_DURATION = 24 * 60 * 60 * 1000L
    }

    /**
     * Check if permissions have been checked in this app session
     */
    fun hasCheckedPermissionsThisSession(): Boolean {
        return prefs.getBoolean(KEY_PERMISSIONS_CHECKED_THIS_SESSION, false)
    }

    /**
     * Mark that permissions have been checked in this session
     */
    fun markPermissionsChecked() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_CHECKED_THIS_SESSION, true).apply()
    }

    /**
     * Reset the session flag (call this when app is fully closed)
     */
    fun resetSession() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_CHECKED_THIS_SESSION, false).apply()
    }

    /**
     * Check if notification permission dialog was dismissed recently (within 24h)
     */
    fun isNotificationDismissedRecently(): Boolean {
        val dismissedTime = prefs.getLong(KEY_NOTIFICATION_DISMISSED_TIME, 0L)
        return (System.currentTimeMillis() - dismissedTime) < DISMISS_DURATION
    }

    /**
     * Mark notification permission dialog as dismissed
     */
    fun markNotificationDismissed() {
        prefs.edit().putLong(KEY_NOTIFICATION_DISMISSED_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Check if exact alarm permission dialog was dismissed recently (within 24h)
     */
    fun isExactAlarmDismissedRecently(): Boolean {
        val dismissedTime = prefs.getLong(KEY_EXACT_ALARM_DISMISSED_TIME, 0L)
        return (System.currentTimeMillis() - dismissedTime) < DISMISS_DURATION
    }

    /**
     * Mark exact alarm permission dialog as dismissed
     */
    fun markExactAlarmDismissed() {
        prefs.edit().putLong(KEY_EXACT_ALARM_DISMISSED_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Check if battery optimization dialog was dismissed recently (within 24h)
     */
    fun isBatteryOptimizationDismissedRecently(): Boolean {
        val dismissedTime = prefs.getLong(KEY_BATTERY_OPTIMIZATION_DISMISSED_TIME, 0L)
        return (System.currentTimeMillis() - dismissedTime) < DISMISS_DURATION
    }

    /**
     * Mark battery optimization dialog as dismissed
     */
    fun markBatteryOptimizationDismissed() {
        prefs.edit().putLong(KEY_BATTERY_OPTIMIZATION_DISMISSED_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Clear all dismissal timestamps (useful for testing or reset)
     */
    fun clearAllDismissals() {
        prefs.edit()
            .remove(KEY_NOTIFICATION_DISMISSED_TIME)
            .remove(KEY_EXACT_ALARM_DISMISSED_TIME)
            .remove(KEY_BATTERY_OPTIMIZATION_DISMISSED_TIME)
            .apply()
    }
}

