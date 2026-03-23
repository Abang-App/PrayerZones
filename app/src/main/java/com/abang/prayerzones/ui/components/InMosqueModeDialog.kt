package com.abang.prayerzones.ui.components

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.ui.screens.GeofenceEntryPoint
import com.abang.prayerzones.util.PrayerFilter
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Dialog that lets the user choose an In-Mosque audio profile.
 *
 * Profiles:
 *   1. Silent        – RINGER_MODE_SILENT  (requires DND access)
 *   2. Vibrate only   – RINGER_MODE_VIBRATE (requires DND access)
 *   3. Lower Volume   – Reduces volume by configured % (protects ALARM stream)
 *
 * On Save: 1.5-second preview plays automatically (for Vibrate mode).
 * DND gate: Silent/Vibrate redirect to system settings if access not granted.
 */
@Composable
fun InMosqueModeDialog(
    onDismiss: () -> Unit,
    mosque: Mosque,
    onModeSaved: (InMosqueMode) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs   = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val nm      = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val scope   = rememberCoroutineScope()

    val displayModes = listOf(
        InMosqueMode.SILENT,
        InMosqueMode.VIBRATE,
        InMosqueMode.LOWER_VOLUME
    )

    var selectedMode by remember {
        val stored = InMosqueMode.fromPref(prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue))
        // Default to LOWER_VOLUME when the feature is being enabled for the first time
        mutableStateOf(
            if (stored == InMosqueMode.DISABLED) InMosqueMode.LOWER_VOLUME else stored
        )
    }

    var showDndPrompt by remember { mutableStateOf(false) }
    var pendingMode   by remember { mutableStateOf<InMosqueMode?>(null) }

    // ── DND prompt ───────────────────────────────────────────────────────────
    if (showDndPrompt) {
        AlertDialog(
            onDismissRequest = { showDndPrompt = false },
            title   = { Text("Permission Required") },
            text    = { Text(
                "\"${pendingMode?.label}\" requires Do Not Disturb / Notification Policy access.\n\n" +
                "Tap OK to open system settings and grant this permission, " +
                "then return here to save your choice."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    showDndPrompt = false
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showDndPrompt = false }) { Text("Cancel") }
            }
        )
    }

    // ── Main dialog ──────────────────────────────────────────────────────────
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📵 Auto In-Mosque Mode",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            ) {
                // ── Explanation (per spec) ────────────────────────────────
                val minDistance = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("pref_min_distance_mosque", InMosqueMode.DEFAULT_MIN_DISTANCE_METERS) ?: InMosqueMode.DEFAULT_MIN_DISTANCE_METERS
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Text(
                        text = "Automatically manage your phone's ringer when you are within " +
                               "${minDistance}m of ${mosque.name} during prayer windows.\n\n" +
                               "Note: This affects system calls/alerts, but your App Prayer " +
                               "Notifications remain active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))

                // ── Radio options ─────────────────────────────────────────
                displayModes.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedMode == mode),
                                onClick  = { selectedMode = mode },
                                role     = Role.RadioButton
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedMode == mode),
                            onClick  = null
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text  = "${mode.emoji}  ${mode.label}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text  = modeDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val mode = selectedMode ?: return@TextButton
                    // DND gate
                    val needsDnd = mode == InMosqueMode.SILENT ||
                                   mode == InMosqueMode.VIBRATE
                    if (needsDnd && !nm.isNotificationPolicyAccessGranted) {
                        pendingMode   = mode
                        showDndPrompt = true
                        return@TextButton
                    }

                    // Persist
                    prefs.edit()
                        .putString(InMosqueMode.PREF_KEY, mode.prefValue)
                        .apply()

                    // Wire geofence with precise window end time
                    runCatching {
                        val windowMinutes = prefs.getString(
                            "pref_near_prayer_time", InMosqueMode.DEFAULT_NEAR_PRAYER_MIN
                        )!!.toLong()

                        // Find the next prayer time in the mosque's timezone
                        val zoneId = try { ZoneId.of(mosque.timeZone) } catch (_: Exception) { ZoneId.systemDefault() }
                        val now = ZonedDateTime.now(zoneId)
                        val today = now.toLocalDate()

                        // Use the standard 6-prayer display order to find the next prayer
                        val cachedTimings = prefs.getStringSet("cached_timings_${mosque.id}", null)
                        // Build a simple map from defaultTimings or fallback
                        val timingMap = mosque.defaultTimings ?: emptyMap()

                        var nextPrayerTimeMs = 0L
                        for (prayerName in PrayerFilter.getDisplayOrder()) {
                            if (prayerName == "Duha") continue // skip Duha for geofence
                            val timeStr = timingMap[prayerName] ?: continue
                            try {
                                val lt = LocalTime.parse(timeStr.take(5))
                                var prayerZdt = ZonedDateTime.of(today, lt, zoneId)
                                if (prayerZdt.isBefore(now.minusMinutes(windowMinutes))) continue
                                // Found a prayer that's within or ahead of the current window
                                nextPrayerTimeMs = prayerZdt.toInstant().toEpochMilli()
                                break
                            } catch (_: Exception) { /* skip bad format */ }
                        }

                        val windowEndMs = if (nextPrayerTimeMs > 0L) {
                            nextPrayerTimeMs + windowMinutes * 60_000L
                        } else {
                            // No upcoming prayer found — use a conservative 2-hour window
                            System.currentTimeMillis() + 2 * 60 * 60_000L
                        }

                        EntryPointAccessors
                            .fromApplication(context.applicationContext, GeofenceEntryPoint::class.java)
                            .geofenceManager()
                            .addGeofence(mosque, prayerTimeUtcMillis = nextPrayerTimeMs, windowEndUtcMillis = windowEndMs)
                    }

                    // 1.5-second preview, then notify caller
                    scope.launch {
                        playPreview(context, mode)
                        onModeSaved(mode)
                    }

                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                // User is disabling the feature — must restore audio immediately if currently active
                val entryPoint = EntryPointAccessors
                    .fromApplication(context.applicationContext, GeofenceEntryPoint::class.java)

                // Check if mode is currently active (phone is silenced/vibrate)
                val isCurrentlyActive = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)

                if (isCurrentlyActive) {
                    Log.d("InMosqueModeDialog", "User disabled mode while active — restoring audio")
                    entryPoint.inMosqueModeManager().onWindowEnd() // Restore audio + clear state
                }

                // Set mode to DISABLED
                prefs.edit()
                    .putString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                    .apply()

                // Remove geofence
                runCatching {
                    entryPoint.geofenceManager().removeGeofence(mosque.id)
                }

                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

// ── Preview helper ────────────────────────────────────────────────────────────

private suspend fun playPreview(context: Context, mode: InMosqueMode) {
    when (mode) {
        InMosqueMode.DISABLED -> { /* nothing */ }

        InMosqueMode.SILENT -> {
            // Silent has no audible preview by definition — just a short delay
            delay(300)
        }

        InMosqueMode.VIBRATE -> {
            vibrate(context, 1500)   // 1.5-second vibration preview
            delay(1600)
        }

        InMosqueMode.LOWER_VOLUME -> {
            // Lower Volume mode has no immediate preview - it only takes effect
            // when entering the mosque geofence
            delay(300)
        }
    }
}


private fun vibrate(context: Context, ms: Long) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S  -> vibrateApi31(context, ms)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O  -> vibrateApi26(context, ms)
        else                                             -> vibrateLegacy(context, ms)
    }
}

@SuppressLint("MissingPermission")
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun vibrateApi31(context: Context, ms: Long) {
    val vm = context.getSystemService(VibratorManager::class.java)
    vm?.defaultVibrator?.vibrate(
        VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
    )
}

@SuppressLint("MissingPermission")
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private fun vibrateApi26(context: Context, ms: Long) {
    @Suppress("DEPRECATION")
    val v = context.getSystemService(Vibrator::class.java)
    v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun vibrateLegacy(context: Context, ms: Long) {
    val v = context.getSystemService(Vibrator::class.java)
    v?.vibrate(ms)
}

private fun modeDescription(mode: InMosqueMode): String = when (mode) {
    InMosqueMode.SILENT       -> "Phone goes completely silent when you enter"
    InMosqueMode.VIBRATE      -> "Phone switches to vibrate when you enter"
    InMosqueMode.LOWER_VOLUME -> "Reduces volume to configured percentage when you enter"
    InMosqueMode.DISABLED     -> "In-Mosque Mode is off"
}
