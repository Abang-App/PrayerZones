package com.abang.prayerzones.ui.components

import android.content.pm.PackageInfo
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.viewmodel.MosqueDetailViewModel
import com.abang.prayerzones.viewmodel.PrayerType
import com.abang.prayerzones.viewmodel.PrayerUiState
import androidx.preference.PreferenceManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.abang.prayerzones.R
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import com.abang.prayerzones.util.YouTubeLiveHelper

@Composable
//private fun Section2_PrayerTimes_Integrated(
fun Section2_PrayerTimes(
    viewModel: MosqueDetailViewModel,
    mosque: Mosque,
    isFirstMosque: Boolean,
    modifier: Modifier = Modifier
) {
    val prayerRows by viewModel.prayerUiState.collectAsState()
    val activePrayer by viewModel.activePrayer.collectAsState(initial = null)
    val muteStates by viewModel.muteStates.collectAsState()

    // Observe ticker to force recomposition for active prayer state updates
    val currentTimestamp by viewModel.currentTimestamp.collectAsState()
    val now = currentTimestamp

    // --- Reactive Global Settings Snapshot ---
    // These MUST be Compose state so icon rendering recomposes when returning from Settings.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPrefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    var slot0MasterEnabled: Boolean by remember { mutableStateOf(sharedPrefs.getBoolean("pref_notifications_enabled", true)) }
    var secondaryMasterEnabled: Boolean by remember { mutableStateOf(sharedPrefs.getBoolean("pref_secondary_notifications_enabled", true)) }
    var globalMuteDuha: Boolean by remember { mutableStateOf(sharedPrefs.getBoolean("pref_mute_duha", false)) }
    var mainSlotAudioStyle: String by remember { mutableStateOf(sharedPrefs.getString("pref_main_slot_audio_style", "azan") ?: "azan") }
    var secondaryAudioMode: String by remember { mutableStateOf(sharedPrefs.getString("pref_secondary_audio_mode", "tone") ?: "tone") }

    // 1. Get the Scope and Context for the click handler


    fun refreshGlobalSettings() {
        slot0MasterEnabled = sharedPrefs.getBoolean("pref_notifications_enabled", true)
        secondaryMasterEnabled = sharedPrefs.getBoolean("pref_secondary_notifications_enabled", true)
        globalMuteDuha = sharedPrefs.getBoolean("pref_mute_duha", false)
        mainSlotAudioStyle = sharedPrefs.getString("pref_main_slot_audio_style", "azan") ?: "azan"
        secondaryAudioMode = sharedPrefs.getString("pref_secondary_audio_mode", "tone") ?: "tone"
    }


    // [NEW] Build Signature Color Logic: Runtime signature (no BuildConfig dependency)
    val signatureColorInfo = remember(mosque.id) {
        val palette = listOf(
            Color(0xFF6A1B9A), // purple
            Color(0xFF0277BD), // blue
            Color(0xFF00695C), // teal
            Color(0xFFE65100), // orange
            Color(0xFFAD1457), // magenta
            Color(0xFF5D4037), // brown
            Color(0xFF2E7D32)  // green
        )

        // Use versionName + lastUpdateTime as a stable-enough build signature.
        val (versionName, lastUpdateTime) = runCatching {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            (pInfo.versionName ?: "0") to pInfo.lastUpdateTime
        }.getOrDefault("0" to 0L)

        val seed = "$versionName|$lastUpdateTime"
        val index = abs(seed.hashCode()) % palette.size
        Pair(palette[index], index)
    }

    android.util.Log.i(
        "BuildColor",
        "signature index=${signatureColorInfo.second} seed=versionName/lastUpdateTime"
    )

    val signatureColor = signatureColorInfo.first

    // Refresh when coming back from Settings.
    DisposableEffect(lifecycleOwner, sharedPrefs) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshGlobalSettings()
                // ✅ Force a data refresh so isActive is recalculated immediately after unlock.
                viewModel.forceRefreshTicker()
            }
        }

        val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "pref_notifications_enabled",
                "pref_secondary_notifications_enabled",
                "pref_mute_duha" -> refreshGlobalSettings()
                "pref_main_slot_audio_style" -> refreshGlobalSettings()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)

        // One immediate refresh to ensure first frame reflects current toggles
        refreshGlobalSettings()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
    }

    // Auto-fit (crowded) mode: used for Friday split layouts (up to ~8 rows)
    val isCrowded = prayerRows.size > 6
    val rowPadding = if (isCrowded) 4.dp else 12.dp
    val primaryWeight = if (isCrowded) 1.0f else 1.2f
    val secondaryWeight = 0.8f


    // Use Column instead of LazyColumn for proportional sizing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = rowPadding)
    ) {
        prayerRows.forEach { prayer ->
            val uniqueKey = "${mosque.id}|${prayer.prayerKey}"
            val isMuted = muteStates[uniqueKey] ?: false

            val globalGateEnabled: Boolean = if (isFirstMosque) slot0MasterEnabled else secondaryMasterEnabled
            val globalDuhaMuted: Boolean = (prayer.prayerKey == "Duha" && globalMuteDuha)

            val showMutedIcon = (!globalGateEnabled) || globalDuhaMuted || isMuted

            val isActive = prayer.isActive

            // Assign proportional weight: primaries get more space than secondaries
            val rowWeight = when (prayer.type) {
                PrayerType.PRIMARY -> primaryWeight
                PrayerType.SECONDARY -> secondaryWeight
            }

            // Top border for visual separation (always render, including first item)
            HorizontalDivider(thickness = 0.5.dp)

            // Simple error presentation row
            if (prayer.errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = prayer.errorMessage ?: "Prayer time error",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(rowWeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PrayerRow(
                        prayer = prayer,
                        isMuted = showMutedIcon,
                        onToggleMute = {
                            if (globalGateEnabled && !globalDuhaMuted) {
                                viewModel.toggleMute(mosque.id, prayer.prayerKey)
                            }
                        },
                        isFirstMosque = isFirstMosque,
                        isActive = isActive,
                        globalGateEnabled = globalGateEnabled,
                        globalDuhaMuted = globalDuhaMuted,
                        mainSlotAudioStyle = mainSlotAudioStyle,
                        secondaryAudioMode = secondaryAudioMode,
                        hasLiveStream = mosque.channelId.isNotBlank(),
                        onWatchLiveClick = {
                            scope.launch {
                                val channelId = mosque.channelId
                                if (channelId.isBlank()) return@launch

                                // Inform user the app is performing the reachability check
                                android.widget.Toast.makeText(
                                    context,
                                    "Checking stream…",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()

                                // Resolve URL: cache-first → HEAD validation → Firestore refresh
                                // Returns null if both cached URL and Firestore URL are unreachable
                                val safeUrl: String? = try {
                                    YouTubeLiveHelper.getCachedOrFetch(context, channelId)
                                } catch (e: Exception) {
                                    android.util.Log.e("YouTubeLiveHelper", "getCachedOrFetch threw unexpectedly", e)
                                    null
                                }

                                // Null → stream confirmed offline, do not launch Intent
                                if (safeUrl == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Stream offline. Please wait for the new broadcast to start.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }

                                // Launch YouTube app in its own task stack
                                fun buildYouTubeIntent(target: String) =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                                        setPackage("com.google.android.youtube")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                                    }

                                fun buildBrowserIntent(target: String) =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }

                                try {
                                    context.startActivity(buildYouTubeIntent(safeUrl))
                                } catch (e: Exception) {
                                    // YouTube app not installed — open in browser
                                    android.util.Log.w(
                                        "YouTubeLiveHelper",
                                        "YouTube app unavailable, opening browser for $safeUrl", e
                                    )
                                    try {
                                        context.startActivity(buildBrowserIntent(safeUrl))
                                    } catch (_: Exception) { /* nothing to do */ }
                                }
                            }
                        },
                        // Step 3: Pass fillMaxHeight modifier to stretch purple background
                        modifier = Modifier.fillMaxHeight(),
                        signatureColor = signatureColor
                    )
                }
            }
        }
        // Bottom border to close the section
        HorizontalDivider(thickness = 0.5.dp)
    }
}

//-----------------        -----------------------

@Composable
private fun PrayerRow(
    prayer: PrayerUiState,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    isFirstMosque: Boolean,
    isActive: Boolean,
    globalGateEnabled: Boolean,
    globalDuhaMuted: Boolean,
    mainSlotAudioStyle: String,
    secondaryAudioMode: String,
    hasLiveStream: Boolean,
    onWatchLiveClick: () -> Unit,
    signatureColor: Color,
    modifier: Modifier = Modifier
) {
    when (prayer.type) {
        PrayerType.PRIMARY -> PrimaryPrayerRow(
            prayer = prayer,
            isMuted = isMuted,
            onToggleMute = onToggleMute,
            isFirstMosque = isFirstMosque,
            isActive = isActive,
            globalGateEnabled = globalGateEnabled,
            globalDuhaMuted = globalDuhaMuted,
            mainSlotAudioStyle = mainSlotAudioStyle,
            secondaryAudioMode = secondaryAudioMode,
            hasLiveStream = hasLiveStream,
            onWatchLiveClick = onWatchLiveClick,
            modifier = modifier.fillMaxHeight(),
            signatureColor = signatureColor
        )

        PrayerType.SECONDARY -> SecondaryPrayerRow(
            prayer = prayer,
            isMuted = isMuted,
            onToggleMute = onToggleMute,
            isActive = isActive,
            globalGateEnabled = globalGateEnabled,
            globalDuhaMuted = globalDuhaMuted,
            secondaryAudioMode = secondaryAudioMode,
            hasLiveStream = hasLiveStream,
            onWatchLiveClick = onWatchLiveClick,
            modifier = modifier
        )
    }
}

@Composable
private fun PrimaryPrayerRow(
    prayer: PrayerUiState,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    isFirstMosque: Boolean,
    isActive: Boolean,
    globalGateEnabled: Boolean,
    globalDuhaMuted: Boolean,
    mainSlotAudioStyle: String,
    secondaryAudioMode: String,
    hasLiveStream: Boolean,
    onWatchLiveClick: () -> Unit,
    signatureColor: Color,
    modifier: Modifier = Modifier
) {
    val highlightColor = if (prayer.isNext) signatureColor.copy(alpha = 0.25f) else Color.Transparent

    // Use the Theme's default high-contrast text color (Black in Light / White in Dark)
    val themeTextColor = MaterialTheme.colorScheme.onSurface

    val finalTextColor = when {
        isActive -> Color(0xFF50C878) // Keep the emeraldGreen  Green as requested
        prayer.errorMessage != null -> Color.Red
        prayer.isApproximate -> Color(0xFFFF8C00) // Orange
        else -> themeTextColor // <--- This will now flip automatically like Section 1
    }

    // Global OFF visual override
    val finalAlpha = if (!globalGateEnabled || globalDuhaMuted) 0.5f else 1.0f

    // Live icon visibility: only when active, not Duha, AND mosque has a channelId
    val showLive = isActive && prayer.prayerKey != "Duha" && hasLiveStream

    val pulse = rememberInfiniteTransition(label = "live_pulse_primary")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_scale_primary"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 64.dp)
            .background(highlightColor)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Base row content (left text + right icon) stays in place
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = prayer.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = finalTextColor
                )
                Text(
                    text = prayer.time,
                    fontSize = 28.sp,
                    fontWeight = if (prayer.isNext) FontWeight.Bold else FontWeight.Normal,
                    color = finalTextColor,
                    modifier = Modifier.offset(y = (-4).dp)
                )
            }

            IconButton(onClick = onToggleMute) {
                // Special case: Duha always uses MusicNote icons (enabled/muted)
                if (prayer.prayerKey == "Duha") {
                    Icon(
                        imageVector = if (isMuted) Icons.Filled.MusicOff else Icons.Filled.MusicNote,
                        contentDescription = if (isMuted) "Unmute Duha" else "Mute Duha",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                }
                // Priority 1: Muted state always shows muted icons
                else if (isMuted) {
                    Icon(
                        imageVector = if (isFirstMosque) Icons.Filled.NotificationsOff else Icons.Filled.MusicOff,
                        contentDescription = "Unmute prayer",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                } else {
                    // Priority 2: Show icon based on audio mode
                    if (isFirstMosque) {
                        // Slot 0 (Main/Favorite Mosque)
                        when (mainSlotAudioStyle) {
                            "azan" -> Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = "Azan enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            "tone" -> Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = "Tone enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            "tts" -> Icon(
                                painter = painterResource(id = R.drawable.ic_tts),
                                contentDescription = "TTS enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            else -> Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = "Default notification",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                        }
                    } else {
                        // Slots 1-3 (Secondary Mosques)
                        when (secondaryAudioMode) {
                            "tone" -> Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = "Tone enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            "tts" -> Icon(
                                painter = painterResource(id = R.drawable.ic_tts),
                                contentDescription = "TTS voice enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            "tone_tts" -> Icon(
                                painter = painterResource(id = R.drawable.ic_tone_tts),
                                contentDescription = "Tone + TTS enabled",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                            else -> Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = "Default tone",
                                modifier = Modifier.alpha(finalAlpha)
                            )
                        }
                    }
                }
            }
        }

        // Floating live icon
        if (showLive) {
            IconButton(
                onClick = onWatchLiveClick,
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(pulseScale)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_playstream),
                    contentDescription = "Live stream",
                    tint = Color.Red
                )
            }
        }
    }
}

@Composable
private fun SecondaryPrayerRow(
    prayer: PrayerUiState,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    isActive: Boolean,
    globalGateEnabled: Boolean,
    globalDuhaMuted: Boolean,
    secondaryAudioMode: String,
    hasLiveStream: Boolean,
    onWatchLiveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val highlightColor = if (prayer.isNext) Color(0xFF90EE90).copy(alpha = 0.4f) else Color.Transparent

    // Use the Theme's default high-contrast text color (Black in Light / White in Dark)
    val themeTextColor = MaterialTheme.colorScheme.onSurface

    val finalTextColor = when {
        isActive -> Color(0xFF50C878) // Keep the emeraldGreen  Green as requested
        prayer.errorMessage != null -> Color.Red
        prayer.isApproximate -> Color(0xFFFF8C00) // Orange
        else -> themeTextColor // <--- This will now flip automatically like Section 1
    }

    // Global OFF visual override
    val finalAlpha = if (!globalGateEnabled || globalDuhaMuted) 0.5f else 1.0f

    // Live stream active window logic: only show if active, not Duha, AND mosque has a channelId
    val isLiveExcluded = prayer.prayerKey == "Duha"
    val showLive = isActive && !isLiveExcluded && hasLiveStream

    val pulse = rememberInfiniteTransition(label = "live_pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_scale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(highlightColor)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Center the text block
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${prayer.displayName} starts ${prayer.time}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (prayer.isNext) FontWeight.Bold else FontWeight.Normal,
                color = finalTextColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )

            // Floating live icon in the center, without shifting the text positions
            if (showLive) {
                IconButton(
                    onClick = onWatchLiveClick,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .scale(pulseScale)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_playstream),
                        contentDescription = "Live stream",
                        tint = Color.Red
                    )
                }
            }
        }

        // Icon always pinned to the far right
        IconButton(onClick = onToggleMute) {
            // Special case: Duha always uses MusicNote icons (enabled/muted)
            if (prayer.prayerKey == "Duha") {
                Icon(
                    imageVector = if (isMuted) Icons.Filled.MusicOff else Icons.Filled.MusicNote,
                    contentDescription = if (isMuted) "Unmute Duha" else "Mute Duha",
                    modifier = Modifier.alpha(finalAlpha)
                )
            }
            // Priority 1: Muted state always shows muted icon
            else if (isMuted) {
                Icon(
                    imageVector = Icons.Filled.NotificationsOff,
                    contentDescription = "Unmute prayer",
                    modifier = Modifier.alpha(finalAlpha)
                )
            } else {
                // Priority 2: Show icon based on secondary audio mode
                when (secondaryAudioMode) {
                    "tone" -> Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Tone enabled",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                    "tts" -> Icon(
                        painter = painterResource(id = R.drawable.ic_tts),
                        contentDescription = "TTS voice enabled",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                    "tone_tts" -> Icon(
                        painter = painterResource(id = R.drawable.ic_tone_tts),
                        contentDescription = "Tone + TTS enabled",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Default tone",
                        modifier = Modifier.alpha(finalAlpha)
                    )
                }
            }
        }
    }
}