package com.abang.prayerzones.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BatteryOptimization"
    }

    /**
     * Check if the app is ignoring battery optimizations
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                // Battery optimization doesn't exist before Android M
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization status", e)
            false
        }
    }

    /**
     * Request battery optimization exemption from the user
     * Shows system dialog asking user to disable battery optimization for this app
     */
    fun requestBatteryOptimizationExemption(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!isIgnoringBatteryOptimizations()) {
                    // Try to open the specific app exemption dialog first
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:${activity.packageName}")
                        activity.startActivity(intent)
                        Log.d(TAG, "Opened battery optimization exemption dialog")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open specific exemption dialog, opening settings", e)
                        // Fallback: Open battery optimization settings page
                        openBatteryOptimizationSettings(activity)
                    }
                } else {
                    Log.d(TAG, "App is already ignoring battery optimizations")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization exemption", e)
        }
    }

    /**
     * Open battery optimization settings page
     * User has to manually find and whitelist the app
     */
    fun openBatteryOptimizationSettings(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                activity.startActivity(intent)
                Log.d(TAG, "Opened battery optimization settings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening battery optimization settings", e)
        }
    }

    /**
     * Get manufacturer-specific battery optimization instructions
     */
    fun getManufacturerInstructions(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                "Xiaomi/MIUI: Go to Security app → Battery saver → App battery saver → Find PrayerZones → No restrictions"

            manufacturer.contains("samsung") ->
                "Samsung: Settings → Apps → PrayerZones → Battery → Optimize battery usage → Turn OFF"

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "Huawei/Honor: Settings → Battery → App launch → PrayerZones → Manage manually (enable all 3 options)"

            manufacturer.contains("oppo") ->
                "OPPO: Settings → Battery → App Battery Management → PrayerZones → Don't optimize"

            manufacturer.contains("vivo") ->
                "Vivo: Settings → Battery → Background power consumption → PrayerZones → Allow background activity"

            manufacturer.contains("oneplus") ->
                "OnePlus: Settings → Battery → Battery optimization → PrayerZones → Don't optimize"

            else ->
                "Please disable battery optimization for PrayerZones to ensure reliable prayer time notifications"
        }
    }

    /**
     * Check if this device is known to have aggressive battery optimization
     */
    fun hasAggressiveBatteryManagement(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("huawei") ||
                manufacturer.contains("honor") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("vivo") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("samsung")
    }
}

