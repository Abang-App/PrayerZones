package com.abang.prayerzones.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.preference.PreferenceManager
import com.abang.prayerzones.R
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.ui.components.PermissionRequestDialog
import com.abang.prayerzones.ui.components.Section1_HeaderAndCountdown
import com.abang.prayerzones.ui.components.Section2_PrayerTimes
import com.abang.prayerzones.util.PlaybackState
import com.abang.prayerzones.viewmodel.MosqueDetailViewModel

@Composable
fun MosqueScreen(
    mosque: Mosque,
    isFirstMosque: Boolean,
    slotIndex: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: MosqueDetailViewModel? = null
) {
    val context = LocalContext.current
    val actualViewModel: MosqueDetailViewModel = viewModel ?: hiltViewModel(
        key = "mosque_${mosque.id}_$isFirstMosque"
    )

    LaunchedEffect(Unit) {
        actualViewModel.initializeIfNeeded(mosque, isFirstMosque, slotIndex)
    }

    val isAzanPlaying     by PlaybackState.isAzanPlaying.collectAsState()
    val playingPrayerName by PlaybackState.currentPrayerName.collectAsState()
    val playingMosqueId   by PlaybackState.currentMosqueId.collectAsState()

    val showNotificationDialog        by actualViewModel.showNotificationPermissionDialog.collectAsState()
    val showExactAlarmDialog          by actualViewModel.showExactAlarmPermissionDialog.collectAsState()
    val showBatteryOptimizationDialog by actualViewModel.showBatteryOptimizationDialog.collectAsState()

    LaunchedEffect(mosque) {
        actualViewModel.checkAndRequestPermissions()
    }

    // ── Accent colour for mosque name in Section 1 (Slot 0 only) ────────────
    // Proximity-Gated Color Matrix (Orange Trap–safe):
    //   !featureEnabled                              → Normal (feature OFF)
    //   !isInsideWindow                              → Normal (no prayer window — kills Orange Trap)
    //   isInsideWindow && distance > nearBoundary    → Normal (in window but too far)
    //   isInsideWindow && distance ≤ insideBoundary  → Green  (at mosque during prayer)
    //   isInsideWindow && near but not inside        → Orange (approaching mosque during prayer)
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val prayerStates by actualViewModel.prayerUiState.collectAsState()
    val currentTick  by actualViewModel.currentTimestamp.collectAsState()

    val accentColor: Color = if (isFirstMosque) {
        var modeEnabled by remember {
            mutableStateOf(
                InMosqueMode.fromPref(
                    prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                ) != InMosqueMode.DISABLED
            )
        }
        var activeInside by remember {
            mutableStateOf(prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false))
        }
        var withinWindowFromBg by remember {
            mutableStateOf(prefs.getBoolean(
                com.abang.prayerzones.util.InMosqueModeManager.PREF_WITHIN_WINDOW, false))
        }
        var lastDistanceM by remember {
            mutableStateOf(prefs.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE))
        }

        // Listen for SharedPreferences changes for proactive UI updates
        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    InMosqueMode.PREF_ACTIVE -> {
                        activeInside = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)
                        android.util.Log.d("IN_MOSQUE_DEBUG", "UI Received PREF_ACTIVE update: $activeInside")
                    }
                    InMosqueMode.PREF_KEY -> {
                        modeEnabled = InMosqueMode.fromPref(
                            prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                        ) != InMosqueMode.DISABLED
                    }
                    com.abang.prayerzones.util.InMosqueModeManager.PREF_WITHIN_WINDOW -> {
                        withinWindowFromBg = prefs.getBoolean(
                            com.abang.prayerzones.util.InMosqueModeManager.PREF_WITHIN_WINDOW, false)
                    }
                    InMosqueMode.PREF_LAST_DISTANCE -> {
                        lastDistanceM = prefs.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
                        android.util.Log.d("IN_MOSQUE_DEBUG", "UI Received PREF_LAST_DISTANCE update: ${lastDistanceM.toInt()}m")
                    }
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)

            // ✅ FIX: Perform one manual read AFTER registering the listener to catch up
            // if the state changed while the app was in the background/locked.
            activeInside = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)
            modeEnabled = InMosqueMode.fromPref(
                prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
            ) != InMosqueMode.DISABLED
            withinWindowFromBg = prefs.getBoolean(
                com.abang.prayerzones.util.InMosqueModeManager.PREF_WITHIN_WINDOW, false)
            lastDistanceM = prefs.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
            android.util.Log.d("IN_MOSQUE_DEBUG", "UI init read: activeInside=$activeInside, modeEnabled=$modeEnabled, withinWindowFromBg=$withinWindowFromBg, lastDistanceM=${lastDistanceM.toInt()}m, isFirstMosque=$isFirstMosque")

            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Compute "isInsideWindow" from prayer times (foreground-aware calculation)
        val nearMinutes = remember(prefs) {
            prefs.getString("pref_near_prayer_time", InMosqueMode.DEFAULT_NEAR_PRAYER_MIN)!!.toLong()
        }
        val isInsideWindow by remember(prayerStates, currentTick, nearMinutes, withinWindowFromBg) {
            derivedStateOf {
                // Compute from prayer times visible in the UI (real-time clock check)
                if (prayerStates.isEmpty()) {
                    // No prayer data yet — fall back to the background flag
                    return@derivedStateOf withinWindowFromBg
                }
                val mosqueZone = try {
                    java.time.ZoneId.of(mosque.timeZone)
                } catch (_: Exception) {
                    java.time.ZoneId.systemDefault()
                }
                val nowInMosque = java.time.ZonedDateTime.now(mosqueZone)
                val windowMs    = nearMinutes * 60 * 1000L
                val todayDate   = nowInMosque.toLocalDate()
                prayerStates.any { prayer ->
                    try {
                        val prayerTime = java.time.LocalTime.parse(prayer.time.take(5))
                        val prayerZdt  = todayDate.atTime(prayerTime).atZone(mosqueZone)
                        val prayerMs   = prayerZdt.toInstant().toEpochMilli()
                        val nowMs      = nowInMosque.toInstant().toEpochMilli()
                        nowMs in (prayerMs - windowMs)..(prayerMs + windowMs)
                    } catch (_: Exception) { false }
                }
            }
        }

        // TASK 1: Read thresholds for proximity-gated color
        // "Near" boundary: dynamically derived from the max value in min_distance_mosque_values (XML)
        val nearBoundaryM = remember {
            context.resources.getStringArray(R.array.min_distance_mosque_values)
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull()?.toFloat() ?: 1000f
        }
        // "Inside" boundary: user-selected pref_min_distance_mosque
        val insideBoundaryM = remember(prefs) {
            prefs.getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS)!!.toFloat()
        }

        // Debug: log the final color decision
        android.util.Log.d("IN_MOSQUE_DEBUG", "Color decision: modeEnabled=$modeEnabled, isInsideWindow=$isInsideWindow, activeInside=$activeInside, lastDistanceM=${lastDistanceM.toInt()}m, nearBoundary=${nearBoundaryM.toInt()}m, insideBoundary=${insideBoundaryM.toInt()}m")

        // ── Proximity-Gated Color Matrix ──
        // CRITICAL: Orange/Green are ONLY possible when isInsideWindow == true.
        // Outside any prayer window the color is ALWAYS Normal, regardless of
        // whatever stale distance sits in PREF_LAST_DISTANCE.  This eliminates
        // the "Orange Trap" where a leftover 50 m reading kept the title Orange
        // indefinitely after the prayer window closed.
        when {
            !modeEnabled                                    -> Color.Unspecified  // Feature disabled → Normal
            !isInsideWindow                                 -> Color.Unspecified  // No prayer window → Normal (kills Orange Trap)
            lastDistanceM > nearBoundaryM                   -> Color.Unspecified  // In window but too far → Normal
            lastDistanceM <= insideBoundaryM                -> Color(0xFF4CAF50)  // Green: at mosque during prayer
            else                                            -> Color(0xFFF57C00)  // Orange: in window, near but not yet at mosque
        }
    } else {
        Color.Unspecified
    }

    Box(modifier.fillMaxSize()) {
        // Permission dialogs
        if (showNotificationDialog) {
            PermissionRequestDialog(
                title = "Notification Permission",
                message = "PrayerZones needs notification permission to alert you at prayer times.",
                onConfirm = { actualViewModel.onNotificationPermissionResult(true) },
                onDismiss = { actualViewModel.onNotificationPermissionResult(false) }
            )
        }
        if (showExactAlarmDialog) {
            PermissionRequestDialog(
                title = "Exact Alarm Permission",
                message = "PrayerZones needs exact alarm permission to ensure alarms fire precisely at prayer times.",
                onConfirm = { actualViewModel.onExactAlarmPermissionResult(true) },
                onDismiss = { actualViewModel.onExactAlarmPermissionResult(false) }
            )
        }
        if (showBatteryOptimizationDialog) {
            PermissionRequestDialog(
                title = "Battery Optimization",
                message = "To ensure prayer time notifications are never missed, please disable battery optimization for PrayerZones.\n\nThis allows the app to play azan reliably, even when your device is in sleep mode or battery saver is active.",
                onConfirm = { actualViewModel.onBatteryOptimizationConfirm(context) },
                onDismiss = { actualViewModel.onBatteryOptimizationResult(false) }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {


            // ── Section 1 — mosque name now tinted by In-Mosque Mode state ──
            Box(modifier = Modifier.weight(0.31f)) {
                Section1_HeaderAndCountdown(
                    mosque     = mosque,
                    viewModel  = actualViewModel,
                    modifier   = Modifier.fillMaxSize(),
                    titleColor = accentColor
                )
            }

            Spacer(Modifier.height(16.dp))
            Section2_PrayerTimes(
                viewModel    = actualViewModel,
                mosque       = mosque,
                isFirstMosque = isFirstMosque,
                modifier     = Modifier.weight(0.69f)
            )
        }

        // ── Stay Reminder Card (Slot 0 only, after prayer window ends) ──
        if (isFirstMosque) {
            val showStayCard by actualViewModel.showStayCard.collectAsState()

            // "Sticky" card: read the near threshold dynamically so card
            // disappears only when user physically leaves the mosque area.
            val nearThresholdForCard = remember {
                context.resources.getStringArray(R.array.min_distance_mosque_values)
                    .mapNotNull { it.toIntOrNull() }
                    .maxOrNull()?.toFloat() ?: 1000f
            }
            // Re-read lastDistanceM from prefs for accurate card gating.
            // (lastDistanceM is already tracked via the SharedPreferences listener above.)
            val stayCardPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
            var lastDistanceForCard by remember {
                mutableStateOf(stayCardPrefs.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE))
            }
            DisposableEffect(stayCardPrefs) {
                val cardDistListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    if (key == InMosqueMode.PREF_LAST_DISTANCE) {
                        lastDistanceForCard = sp.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
                    }
                }
                stayCardPrefs.registerOnSharedPreferenceChangeListener(cardDistListener)
                lastDistanceForCard = stayCardPrefs.getFloat(InMosqueMode.PREF_LAST_DISTANCE, Float.MAX_VALUE)
                onDispose { stayCardPrefs.unregisterOnSharedPreferenceChangeListener(cardDistListener) }
            }

            // FIX 4: Re-sync stay card state from SharedPreferences every time
            // the app enters the foreground (Compose recomposition on resume).
            // This ensures the card persists even if the phone was locked for 20 min.
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        actualViewModel.performStayCardSanityCheck()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            AnimatedVisibility(
                visible = showStayCard && lastDistanceForCard <= nearThresholdForCard,
                enter = expandVertically(),
                exit = shrinkVertically(),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)
            ) {
                StayReminderCard(
                    mosqueName = mosque.name,
                    onDismiss = { actualViewModel.dismissStayCard() }
                )
            }
        }

        AnimatedVisibility(
            visible = isAzanPlaying &&
                    !playingPrayerName.isNullOrBlank() &&
                    (playingMosqueId != null && playingMosqueId == mosque.id),
            enter = slideInVertically(initialOffsetY = { it }),
            exit  = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            playingPrayerName?.let {
                NowPlayingCard(prayerName = it, onStop = { actualViewModel.stopAzan() })
            }
        }
    }
}

@Composable
private fun NowPlayingCard(prayerName: String, onStop: () -> Unit) {
    val isFriday = remember {
        java.time.LocalDate.now().dayOfWeek == java.time.DayOfWeek.FRIDAY
    }
    val displayName = if (isFriday && prayerName == "Dhuhr") "Jumu'ah 1" else prayerName

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$displayName Azan Playing", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop Azan")
                Spacer(Modifier.height(4.dp))
                Text("Stop")
            }
        }
    }
}

/**
 * "Still at Mosque?" reminder card — shown after the prayer window ends
 * if the user is still within the distance threshold.
 */
@Composable
private fun StayReminderCard(mosqueName: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0)  // warm light orange background
        ),
        border = BorderStroke(1.dp, Color(0xFFF57C00)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "🕌 Still at $mosqueName?",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFE65100)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Please remember to keep your phone quiet.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5D4037)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFF57C00)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF57C00))
                ) {
                    Text("OK, Got it")
                }
            }
        }
    }
}
