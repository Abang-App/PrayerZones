package com.abang.prayerzones.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.abang.prayerzones.PrayerCache
import com.abang.prayerzones.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Root settings fragment - Main settings hub
 * Loads root_preferences.xml and handles all main category interactions
 */
@AndroidEntryPoint
class SettingsRootFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var cache: PrayerCache

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = bottomInset)
            insets
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // IMPORTANT: Do NOT set a custom PreferenceDataStore here.
        // We rely on DefaultSharedPreferences for UI-linked global toggles
        // so the main screen + scheduler can read them consistently.

        // Load the preferences from XML
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // Set up click handlers for special preferences
        setupNotificationDetailNavigation()
        setupSystemSettingsLinks()
        setupMosqueManagementLink()
        setupAdvancedOptions()
        setupAboutSection()
        setupThemeListener()
        setupTimeFormatListener()
        setupSupportLink()
    }

    /**
     * Navigation to detailed notification settings screen
     */
    private fun setupNotificationDetailNavigation() {
        findPreference<Preference>("pref_notifications_detail")?.apply {
            setOnPreferenceClickListener {
                // Navigate to NotificationSettingsFragment
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, NotificationSettingsFragment())
                    .addToBackStack(null)
                    .commit()

                // Update toolbar title
                (activity as? SettingsActivity)?.supportActionBar?.title = "Notifications & Sounds"
                true
            }
        }
    }

    /**
     * System settings links (notification channels, battery, alarms)
     */
    private fun setupSystemSettingsLinks() {
        // Open Android notification settings
        findPreference<Preference>("pref_open_notification_settings")?.apply {
            setOnPreferenceClickListener {
                openNotificationSettings()
                true
            }
        }

        // Battery optimization
        findPreference<Preference>("pref_battery_optimization")?.apply {
            setOnPreferenceClickListener {
                openBatteryOptimizationSettings()
                true
            }
        }

        // Exact alarm permission (Android 12+)
        findPreference<Preference>("pref_exact_alarms")?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setOnPreferenceClickListener {
                    openExactAlarmSettings()
                    true
                }
            } else {
                // Hide on older Android versions
                isVisible = false
            }
        }

        // Location permission
        findPreference<Preference>("pref_location_permission")?.apply {
            setOnPreferenceClickListener {
                openAppSettings()
                true
            }
        }
    }

    /**
     * Link to existing Mosque Management screen
     */
    private fun setupMosqueManagementLink() {
        findPreference<Preference>("pref_manage_mosques")?.apply {
            setOnPreferenceClickListener {
                try {
                    val intent = Intent(activity, com.abang.prayerzones.MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.putExtra("start_destination", "mosque")
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsRoot", "Failed to open mosque management", e)
                }
                true
            }
        }
    }

    /**
     * Advanced options (cache clearing, etc.)
     */
    private fun setupAdvancedOptions() {
        // Clear cache
        findPreference<Preference>("pref_clear_cache")?.apply {
            setOnPreferenceClickListener {
                scope.launch {
                    try {
                        cache.clearAllCache()
                        summary = "Cache cleared successfully!"

                        // Reset summary after 2 seconds
                        kotlinx.coroutines.delay(2000)
                        summary = "Force refresh of all prayer times"
                    } catch (e: Exception) {
                        Log.e("SettingsRoot", "Error clearing cache", e)
                        summary = "Error clearing cache"
                    }
                }
                true
            }
        }
    }

        /**
         * About section (version, privacy policy)
         */
        private fun setupAboutSection() {
            // Version preference (read-only)
            val existing = findPreference<Preference>("pref_version")
            if (existing != null) {
                existing.summary = "PrayerZones ${getAppVersion()}"

                // ✅ Replace with long-press capable Preference if needed.
                val parentAndIndex = findParentGroupAndIndex(preferenceScreen, existing.key)
                if (parentAndIndex != null && existing !is LongPressPreference) {
                    val (parent, index) = parentAndIndex

                    val replacement = LongPressPreference(requireContext()).apply {
                        key = existing.key
                        title = existing.title
                        summary = existing.summary
                        isSelectable = existing.isSelectable
                        isEnabled = existing.isEnabled
                        order = existing.order

                        onLongPress = {
                            requireActivity().runOnUiThread {
                                toggleDebugMode()
                            }
                        }
                    }

                    parent.removePreference(existing)
                    parent.addPreference(replacement)
                    // Ensure it appears in the same position
                    replacement.order = index
                } else if (existing is LongPressPreference) {
                    existing.onLongPress = {
                        requireActivity().runOnUiThread {
                            toggleDebugMode()
                        }
                    }
                }
            }

            // Privacy policy link - also supports long-press for priority toggle
            val privacyPref = findPreference<Preference>("pref_privacy_policy")
            if (privacyPref != null) {
                // Replace with LongPressPreference if needed
                val parentAndIndex = findParentGroupAndIndex(preferenceScreen, privacyPref.key)
                if (parentAndIndex != null && privacyPref !is LongPressPreference) {
                    val (parent, index) = parentAndIndex

                    val replacement = LongPressPreference(requireContext()).apply {
                        key = privacyPref.key
                        title = privacyPref.title
                        summary = privacyPref.summary
                        isSelectable = privacyPref.isSelectable
                        isEnabled = privacyPref.isEnabled
                        order = privacyPref.order
                        icon = privacyPref.icon

                        // Regular click: open privacy policy
                        setOnPreferenceClickListener {
                            openPrivacyPolicy()
                            true
                        }

                        // Long-press: toggle priority
                        onLongPress = {
                            requireActivity().runOnUiThread {
                                togglePriority()
                            }
                        }
                    }

                    parent.removePreference(privacyPref)
                    parent.addPreference(replacement)
                    replacement.order = index
                } else if (privacyPref is LongPressPreference) {
                    privacyPref.setOnPreferenceClickListener {
                        openPrivacyPolicy()
                        true
                    }
                    privacyPref.onLongPress = {
                        requireActivity().runOnUiThread {
                            togglePriority()
                        }
                    }
                }
            }
        }

        /**
         * ✅ SECRET DEBUG MODE TOGGLE
         * Long press on VERSION to enable/disable stress test mosque
         * When enabling: Sets Priority 0 (Local/SG first) and clears anchor
         */
        private fun toggleDebugMode() {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            val currentMode = prefs.getBoolean("DEBUG_MODE_ENABLED", false)
            val newMode = !currentMode

            if (newMode) {
                // Enabling debug mode: set priority 0 (Local first) and clear anchor
                prefs.edit()
                    .putBoolean("DEBUG_MODE_ENABLED", true)
                    .putInt("DEBUG_TEST_PRIORITY", 0)
                    .remove("DEBUG_BASE_TIME_SG")
                    .apply()
                android.widget.Toast.makeText(
                    requireContext(),
                    "🔧 Debug Mode: ON\nPriority: LOCAL first (SG→Riyadh)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                // Disabling debug mode: clear everything
                prefs.edit()
                    .putBoolean("DEBUG_MODE_ENABLED", false)
                    .remove("DEBUG_TEST_PRIORITY")
                    .remove("DEBUG_BASE_TIME_SG")
                    .apply()
                android.widget.Toast.makeText(
                    requireContext(),
                    "Debug Mode: OFF",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            Log.d("SettingsRoot", "Debug mode toggled: $newMode")
        }

        /**
         * ✅ PRIORITY QUEUE TOGGLE
         * Long press on PRIVACY POLICY to swap mosque firing order
         * Priority 0 = Local/SG first (default)
         * Priority 1 = Remote/Riyadh first
         * Always clears anchor to force new test cycle at T+2
         */
        private fun togglePriority() {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())

            // Ensure debug mode is enabled
            val debugEnabled = prefs.getBoolean("DEBUG_MODE_ENABLED", false)
            if (!debugEnabled) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "⚠️ Enable Debug Mode first (Long-press Version)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            val currentPriority = prefs.getInt("DEBUG_TEST_PRIORITY", 0)
            val newPriority = if (currentPriority == 0) 1 else 0

            // Clear anchor to force regeneration with new priority starting at T+2
            prefs.edit()
                .putInt("DEBUG_TEST_PRIORITY", newPriority)
                .remove("DEBUG_BASE_TIME_SG")
                .apply()

            val message = if (newPriority == 0) {
                "🔧 Priority: LOCAL first\n(SG immediate → Riyadh +26m)"
            } else {
                "🔧 Priority: REMOTE first\n(Riyadh immediate → SG +26m)"
            }
            android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()

            Log.d("SettingsRoot", "Priority toggled: $newPriority (anchor cleared for immediate restart)")
        }

    // ══════════════════════════════════════════════════════════════
    // SUPPORT DEVELOPMENT LINK
    // ══════════════════════════════════════════════════════════════

    /**
     * Navigate to the Compose-based Support Development screen
     * via MainActivity with a start_destination extra.
     */
    private fun setupSupportLink() {
        findPreference<Preference>("pref_support_development")?.apply {
            setOnPreferenceClickListener {
                try {
                    val intent = Intent(activity, com.abang.prayerzones.MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.putExtra("start_destination", "support")
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("SettingsRoot", "Failed to open support screen", e)
                }
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER METHODS - Intent Launchers
    // ══════════════════════════════════════════════════════════════

    private fun openNotificationSettings() {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            }
        }
        startActivity(intent)
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${requireContext().packageName}")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: Open battery optimization list
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(fallbackIntent)
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${requireContext().packageName}")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general settings
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        startActivity(intent)
    }

    private fun openPrivacyPolicy() {
        // TODO: Replace with your actual privacy policy URL
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://example.com/privacy-policy")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("SettingsRoot", "No browser available", e)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName,
                0
            )
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Custom Preference that supports a native long-press handler.
     */
    class LongPressPreference(context: android.content.Context) : Preference(context) {
        var onLongPress: (() -> Unit)? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.itemView.setOnLongClickListener {
                onLongPress?.invoke()
                true
            }
        }
    }

    private fun findParentGroupAndIndex(
        group: androidx.preference.PreferenceGroup,
        key: String
    ): Pair<androidx.preference.PreferenceGroup, Int>? {
        for (i in 0 until group.preferenceCount) {
            val child = group.getPreference(i)
            if (child.key == key) return group to i
            if (child is androidx.preference.PreferenceGroup) {
                val result = findParentGroupAndIndex(child, key)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * Setup theme preference listener to restart app when theme changes
     */
    private fun setupThemeListener() {
        findPreference<androidx.preference.ListPreference>("pref_theme")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                // Show restart dialog
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Restart Required")
                    .setMessage("Press OK to restart the app and apply the new Theme.")
                    .setPositiveButton("OK") { _, _ ->
                        // Get launch intent
                        val packageManager = requireContext().packageManager
                        val intent = packageManager.getLaunchIntentForPackage(requireContext().packageName)

                        if (intent != null) {
                            // Clear top and create new task
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)

                            // Kill current process cleanly
                            Runtime.getRuntime().exit(0)
                        }
                    }
                    .setCancelable(false) // User must confirm
                    .show()

                true // Allow the change to be persisted before restart
            }
        }
    }

    /**
     * Setup time format preference listener to update time display
     */
    private fun setupTimeFormatListener() {
        findPreference<androidx.preference.SwitchPreferenceCompat>("pref_24_hour_format")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                // The ViewModel will automatically pick up the new preference value
                // on next update cycle, but we can show a toast for user feedback
                requireActivity().runOnUiThread {
                    val format = if (newValue as Boolean) "24-hour" else "12-hour"
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Time format: $format",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                true // Allow the change to be persisted
            }
        }
    }
}
