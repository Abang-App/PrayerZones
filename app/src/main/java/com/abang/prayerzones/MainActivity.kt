package com.abang.prayerzones

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.abang.prayerzones.ui.theme.PrayerZonesTheme
import com.abang.prayerzones.navigation.AppNavGraph
import com.abang.prayerzones.ui.components.Section4_BottomNav
import com.abang.prayerzones.util.FirstLaunchInitializer
import com.abang.prayerzones.util.AlarmSurgeryManager
import com.abang.prayerzones.repository.SupportRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.activity.viewModels
import androidx.preference.PreferenceManager
import com.abang.prayerzones.viewmodel.PrayerViewModel
import com.abang.prayerzones.viewmodel.SupportViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var firstLaunchInitializer: FirstLaunchInitializer

    @Inject
    lateinit var alarmSurgeryManager: AlarmSurgeryManager

    @Inject
    lateinit var supportRepository: SupportRepository

    // One-shot navigation request coming from Settings.
    private var pendingStartDestination: String? = null

    // Hook used to communicate from onNewIntent() to Compose.
    private var navRequestUpdater: ((String?) -> Unit)? = null

    // One-shot pager focus request coming from alarms/notifications.
    private var pendingFocusSlotIndex: Int? = null
    private var pagerFocusUpdater: ((Int?) -> Unit)? = null

    private val prayerViewModel: PrayerViewModel by viewModels()
    private val supportViewModel: SupportViewModel by viewModels()
    private var lastDebugModeEnabled: Boolean? = null

    private val supportRoute = "support"

    private fun consumeStartDestination(intent: Intent?): String? {
        val safeIntent = intent ?: return null
        val requested = safeIntent.getStringExtra("start_destination")
        if (requested.isNullOrBlank()) return null
        Log.d("MainActivity", "Requested start_destination=$requested")
        // One-shot: clear so it doesn't affect back/other flows later.
        safeIntent.removeExtra("start_destination")
        safeIntent.removeExtra("started_from_settings")
        return requested
    }

    private fun consumeFocusSlot(intent: Intent?): Int? {
        val idx = intent?.getIntExtra("focus_slot", -1) ?: -1
        if (idx !in 0..3) return null
        intent?.removeExtra("focus_slot")
        return idx
    }

    // Renamed from handleDonationIntent to handleDeepLink per request
    private fun handleDeepLink(intent: Intent?) {
        Log.d("DeepLink", "Raw URI: ${intent?.data}")

        val data = intent?.data ?: return
        
        // Filter for specific deep link
        if (!data.scheme.equals("myapp", ignoreCase = true) || 
            !data.host.equals("prayer-zones", ignoreCase = true)) {
            return
        }

        // Log Full Query String explicitly for index.html debugging
        Log.d("DeepLink", "Full Query: ${data.query}")

        // Log every parameter explicitly for debugging
        try {
            data.queryParameterNames?.forEach { param ->
                Log.d("DeepLink", "Param: $param = ${data.getQueryParameter(param)}")
            }
        } catch (e: Exception) {
            Log.e("DeepLink", "Error parsing query parameters: ${data.query}", e)
        }

        // Parsing: Extract name and amount. Use amountRaw?.toDoubleOrNull() ?: 0.0
        val name = data.getQueryParameter("name").takeUnless { it.isNullOrBlank() } ?: "Donor"
        val amountDouble = data.getQueryParameter("amount")?.toDoubleOrNull() ?: 0.0
        var amount = amountDouble.toInt()
        val sessionId = data.getQueryParameter("session_id")?.trim()

        // Soften Security Check: If amount is missing/0, default to 3. (Supports "Friend's Fix")
        if (amount <= 0) {
            Log.d("DeepLink", "Amount missing/zero. Defaulting to 3.")
            amount = 3
        }

        Log.d("DeepLink", "Success! Adding Supporter: $name, $amount")
        supportViewModel.addSupporter(name, amount)
        
        // Navigate to support screen
        pendingStartDestination = supportRoute
        navRequestUpdater?.invoke(supportRoute)
        
        // Show Thank You Toast properly
        Toast.makeText(
            this,
            "Thank you $name for your $$amount donation!",
            Toast.LENGTH_LONG
        ).show()

        // Attempt verify session silently for backend record
        if (!sessionId.isNullOrEmpty()) {
            lifecycleScope.launch {
                supportRepository.verifyDonationSession(sessionId, name, amount)
            }
        }

        // One-shot consumption
        intent.data = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture any initial navigation request (one-shot)
        pendingStartDestination = consumeStartDestination(intent)

        // Capture any initial focus request
        pendingFocusSlotIndex = consumeFocusSlot(intent)

        // Handle payment return deep link (myapp://prayer-zones [tip-success])
        handleDeepLink(intent)

        Log.d("AppLaunch", "PrayerZones launched at ${System.currentTimeMillis()}")

        // Initialize default mosques on first launch
        firstLaunchInitializer.initializeIfNeeded()

        // CRITICAL: Install alarms for active mosques (no global cancel)
        alarmSurgeryManager.installActiveAlarms()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        Log.d("ALARM_TEST", "Is PrayerZones exempt from battery optimization? $isIgnoring")

        // Apply night mode delegate based on theme preference
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themePreference = prefs.getString("pref_theme", "system") ?: "system"

        when (themePreference) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        Log.d("MainActivity", "Applied night mode: $themePreference")

        setContent {
            // Read theme preference for Compose
            val darkTheme = when (themePreference) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme() // "system"
            }

            PrayerZonesTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()

                // One-shot pager focus state observable from PrayerZonesScreen.
                val focusSlotState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(pendingFocusSlotIndex) }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    pagerFocusUpdater = { idx -> focusSlotState.value = idx }
                    onDispose { pagerFocusUpdater = null }
                }

                // If SettingsActivity requests a specific destination, navigate once.
                val requestedStartState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(pendingStartDestination) }
                androidx.compose.runtime.LaunchedEffect(requestedStartState.value) {
                    val dest = requestedStartState.value
                    if (!dest.isNullOrBlank() && dest != navController.currentDestination?.route) {
                        try {
                            navController.navigate(dest) {
                                launchSingleTop = true
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to navigate to $dest", e)
                        } finally {
                            // consume
                            requestedStartState.value = null
                            pendingStartDestination = null
                        }
                    }
                 }

                // Keep the state reachable from onNewIntent via this activity field
                androidx.compose.runtime.DisposableEffect(Unit) {
                    // Expose a lambda to update navigation requests on new intents.
                    navRequestUpdater = { route -> requestedStartState.value = route }
                    onDispose { navRequestUpdater = null }
                }

                // Scaffold at the top level - bottom bar stays visible across all screens
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        Section4_BottomNav(navController = navController)
                    }
                ) { innerPadding ->
                    // NavHost content swaps out while bottom bar stays
                    AppNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding),
                        focusSlotState = focusSlotState
                    )
                }
            }
        }

        lastDebugModeEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("DEBUG_MODE_ENABLED", false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ✅ critical: update the Activity's intent reference so future reads don't use stale extras.
        setIntent(intent)
        
        handleDeepLink(intent)

        val dest = consumeStartDestination(intent)
        if (!dest.isNullOrBlank()) {
            pendingStartDestination = dest
            navRequestUpdater?.invoke(dest)
        }

        val focus = consumeFocusSlot(intent)
        if (focus != null) {
            pendingFocusSlotIndex = focus
            pagerFocusUpdater?.invoke(focus)
        }
    }

    override fun onResume() {
        super.onResume()

        val current = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("DEBUG_MODE_ENABLED", false)

        val previous = lastDebugModeEnabled
        if (previous == null || previous != current) {
            Log.w("MainActivity", "DEBUG_MODE_ENABLED changed: $previous -> $current; forcing refreshAllData()")
            lastDebugModeEnabled = current
            prayerViewModel.refreshAllData()
        } else {
            Log.d("MainActivity", "onResume called (debug mode unchanged: $current)")
        }
    }
}
