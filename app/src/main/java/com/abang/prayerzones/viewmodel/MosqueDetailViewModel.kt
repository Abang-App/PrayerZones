package com.abang.prayerzones.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abang.prayerzones.PrayerCache
import com.abang.prayerzones.R
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.NextPrayerInfo
import com.abang.prayerzones.model.PrayerResponse
import com.abang.prayerzones.repository.PrayerRepository
import com.abang.prayerzones.repository.MosqueRepository
import com.abang.prayerzones.util.MediaPlayerManager
import com.abang.prayerzones.util.PermissionHelper
import com.abang.prayerzones.util.PermissionPreferences
import com.abang.prayerzones.util.NotificationHelper
import com.abang.prayerzones.util.AlarmScheduler
import com.abang.prayerzones.util.BatteryOptimizationHelper
import com.abang.prayerzones.util.InMosqueModeManager
import com.abang.prayerzones.util.PrayerFilter
import com.abang.prayerzones.util.TonePlayer
import com.abang.prayerzones.service.TTSAnnouncementService
import com.abang.prayerzones.service.AzanPlaybackService
import com.abang.prayerzones.util.LiveWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MosqueDetailViewModel @Inject constructor(
    private val repository: PrayerRepository,
    private val cache: PrayerCache,
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper,
    private val permissionPreferences: PermissionPreferences,
    private val notificationHelper: NotificationHelper,
    private val alarmScheduler: AlarmScheduler,
    private val batteryOptimizationHelper: BatteryOptimizationHelper,
    private val savedStateHandle: SavedStateHandle,
    private val mosqueRepository: com.abang.prayerzones.repository.MosqueRepository,
    private val inMosqueModeManager: InMosqueModeManager,
) : ViewModel() {

    // Lazy initialization - set by initializeIfNeeded()
    private var _mosque: Mosque? = null
    private var _isFirstMosque: Boolean? = null
    private var _slotIndex: Int = 0   // actual pager page index (0 = local, 1/2/3 = distant)

    val mosque: Mosque? get() = _mosque
    private val isFirstMosque: Boolean get() = _isFirstMosque ?: false

    // Permission dialogs
    private val _showNotificationPermissionDialog = MutableStateFlow(false)
    val showNotificationPermissionDialog: StateFlow<Boolean> = _showNotificationPermissionDialog

    private val _showExactAlarmPermissionDialog = MutableStateFlow(false)
    val showExactAlarmPermissionDialog: StateFlow<Boolean> = _showExactAlarmPermissionDialog

    private val _showBatteryOptimizationDialog = MutableStateFlow(false)
    val showBatteryOptimizationDialog: StateFlow<Boolean> = _showBatteryOptimizationDialog

    // Active prayer tracking
    private val _activePrayer = MutableStateFlow<ActivePrayerState?>(null)
    val activePrayer: StateFlow<ActivePrayerState?> = _activePrayer.asStateFlow()

    // Prayer times state - filtered to exclude Sunrise, Sunset, FirstThird, etc.
    private val _prayerTimes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _prayerUiState = MutableStateFlow<List<PrayerUiState>>(emptyList())
    val prayerUiState: StateFlow<List<PrayerUiState>> = _prayerUiState.asStateFlow()

    // Countdown state
    private val _countdownStrings = MutableStateFlow<Pair<String, String>>("" to "")
    val countdownStrings: StateFlow<Pair<String, String>> = _countdownStrings.asStateFlow()

    // Date string state
    private val _dateString = MutableStateFlow("")
    val dateString: StateFlow<String> = _dateString.asStateFlow()

    // Mute states
    private val _muteStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val muteStates: StateFlow<Map<String, Boolean>> = _muteStates.asStateFlow()

    // Next prayer info
    private val _nextPrayerInfo = MutableStateFlow<NextPrayerInfo?>(null)
    val nextPrayerInfo: StateFlow<NextPrayerInfo?> = _nextPrayerInfo.asStateFlow()

    // Azan playback state
    private val _nowPlayingAzan = MutableStateFlow<String?>(null)
    val nowPlayingAzan: StateFlow<String?> = _nowPlayingAzan.asStateFlow()

    // Azan eligibility
    private val _azanEligibility = MutableStateFlow(false)
    val azanEligibility: StateFlow<Boolean> = _azanEligibility.asStateFlow()

    // Active state ticker - emits current timestamp every 30 seconds to force UI refresh
    private val _currentTimestamp = MutableStateFlow(System.currentTimeMillis())
    val currentTimestamp: StateFlow<Long> = _currentTimestamp.asStateFlow()

    // Timer job tracking for proper cleanup
    private var timerJob: kotlinx.coroutines.Job? = null

    // Add state flag for approximate timings
    private val _isApproximateTiming = MutableStateFlow(false)
    val isApproximateTiming: StateFlow<Boolean> = _isApproximateTiming.asStateFlow()

    // Add error message state for invalid configuration
    private val _configurationError = MutableStateFlow<String?>(null)
    val configurationError: StateFlow<String?> = _configurationError.asStateFlow()

    // "Still at Mosque" card state — observed by the UI
    private val _showStayCard = MutableStateFlow(false)
    val showStayCard: StateFlow<Boolean> = _showStayCard.asStateFlow()

    private val PRAYER_ORDER = listOf("Fajr", "Duha", "Dhuhr", "Asr", "Maghrib", "Isha")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun initializeIfNeeded(mosque: Mosque, isFirstMosque: Boolean, slotIndex: Int = 0) {
        // Cancel previous mosque's timer if switching mosques
        if (_mosque != null && _mosque?.id != mosque.id) {
            timerJob?.cancel()
            timerJob = null
            Log.d("MosqueDetailVM", "[${_mosque?.name}] Cancelled previous timer")
        }

        if (_mosque == null || _mosque?.id != mosque.id) {
            _mosque = mosque
            _isFirstMosque = isFirstMosque
            _slotIndex = slotIndex

            // ✅ Load persisted mute states for this mosque so UI bell reflects DB on cold start.
            viewModelScope.launch {
                mosqueRepository.observePrayerMuteStates(mosque.id).collect { perPrayer ->
                    val base = _muteStates.value.toMutableMap()
                    perPrayer.forEach { (prayerKey, muted) ->
                        base[keyFor(mosque.id, prayerKey)] = muted
                    }
                    _muteStates.value = base
                }
            }

            // Don't store complex objects in SavedStateHandle - only primitives

            updateAzanEligibility()
            updateDateString()
            Log.d("MosqueDetailVM", "[${mosque.name}] ViewModel initialized")

            // Run stay-card sanity check on init (only relevant for Slot 0 / first mosque)
            if (isFirstMosque) {
                performStayCardSanityCheck()
            }

            fetchAllPrayerTimes()

            viewModelScope.launch {
                var timersHaveBeenStarted = false
                _prayerTimes.collect { times ->
                    if (times.isNotEmpty() && !timersHaveBeenStarted) {
                        startTimers()
                        timersHaveBeenStarted = true
                    }
                }
            }
        }
    }

    fun checkAndRequestPermissions() {
        // Only check once per app session to prevent nagging on every swipe
        if (permissionPreferences.hasCheckedPermissionsThisSession()) {
            Log.d("MosqueDetailVM", "Permissions already checked this session, skipping")
            return
        }

        // Mark as checked for this session
        permissionPreferences.markPermissionsChecked()
        Log.d("MosqueDetailVM", "Checking permissions for the first time this session")

        // Check notification permission
        val notificationsAllowed = permissionHelper.areNotificationsPermitted()
        if (!notificationsAllowed && !permissionPreferences.isNotificationDismissedRecently()) {
            _showNotificationPermissionDialog.value = true
            Log.d("MosqueDetailVM", "Showing notification permission dialog")
        } else if (!notificationsAllowed) {
            Log.d("MosqueDetailVM", "Notification permission denied but dismissed recently (within 24h)")
        }

        // Check exact alarm permission
        val canSchedule = permissionHelper.canScheduleExactAlarms()
        if (!canSchedule && !permissionPreferences.isExactAlarmDismissedRecently()) {
            _showExactAlarmPermissionDialog.value = true
            Log.d("MosqueDetailVM", "Showing exact alarm permission dialog")
        } else if (!canSchedule) {
            Log.d("MosqueDetailVM", "Exact alarm permission denied but dismissed recently (within 24h)")
        }

        // Check battery optimization
        val isBatteryOptimized = !permissionHelper.isIgnoringBatteryOptimizations()
        if (isBatteryOptimized && !permissionPreferences.isBatteryOptimizationDismissedRecently()) {
            _showBatteryOptimizationDialog.value = true
            Log.d("MosqueDetailVM", "Showing battery optimization dialog")
        } else if (isBatteryOptimized) {
            Log.d("MosqueDetailVM", "Battery optimization needed but dismissed recently (within 24h)")
        }
    }

    fun onNotificationPermissionResult(isGranted: Boolean) {
        _showNotificationPermissionDialog.value = false
        if (isGranted) {
            // User clicked "Settings" - open notification settings
            try {
                val intent = permissionHelper.getNotificationSettingsIntent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("MosqueDetailVM", "Opened notification settings")
            } catch (e: Exception) {
                Log.e("MosqueDetailVM", "Failed to open notification settings", e)
            }
        } else {
            // User clicked "Not Now" - mark as dismissed for 24 hours
            permissionPreferences.markNotificationDismissed()
            Log.d("MosqueDetailVM", "Notification permission dismissed for 24 hours")
        }
    }

    fun onExactAlarmPermissionResult(isGranted: Boolean) {
        _showExactAlarmPermissionDialog.value = false
        if (isGranted) {
            // User clicked "Settings" - open exact alarm settings
            try {
                val intent = permissionHelper.getExactAlarmSettingsIntent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("MosqueDetailVM", "Opened exact alarm settings")
            } catch (e: Exception) {
                Log.e("MosqueDetailVM", "Failed to open exact alarm settings", e)
            }
        } else {
            // User clicked "Not Now" - mark as dismissed for 24 hours
            permissionPreferences.markExactAlarmDismissed()
            Log.d("MosqueDetailVM", "Exact alarm permission dismissed for 24 hours")
        }
    }

    fun onBatteryOptimizationResult(isGranted: Boolean) {
        _showBatteryOptimizationDialog.value = false
        if (!isGranted) {
            // User clicked "Not Now" - mark as dismissed for 24 hours
            permissionPreferences.markBatteryOptimizationDismissed()
            Log.d("MosqueDetailVM", "Battery optimization dismissed for 24 hours")
        }
    }

    fun onBatteryOptimizationConfirm(context: Context) {
        _showBatteryOptimizationDialog.value = false
        // Need to get Activity from context
        if (context is android.app.Activity) {
            batteryOptimizationHelper.requestBatteryOptimizationExemption(context)
        } else {
            Log.e("MosqueDetailVM", "Context is not an Activity, cannot request battery optimization")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // "Still at Mosque" Card Management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dismiss the "Still at Mosque" stay card. Clears both ViewModel state
     * and the persisted SharedPreferences flag (via InMosqueModeManager).
     * Should be called from the UI when user taps "OK".
     */
    fun dismissStayCard() {
        _showStayCard.value = false
        try {
            inMosqueModeManager.dismissStayCard()
        } catch (e: Exception) {
            Log.w("MosqueDetailVM", "Failed to dismiss stay card in manager", e)
        }
    }

    /**
     * Called on foreground / ON_RESUME.  Checks SharedPreferences to see
     * whether a stay-card notification is outstanding AND still valid.
     *
     * Uses a strict 1-minute grace period after window end.
     * If windowEnd is 0 (cleared during transition), we do NOT auto-dismiss —
     * this prevents a race condition where onWindowEnd() clears the timestamp
     * before the async proximity check posts the card.
     */
    fun performStayCardSanityCheck() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val showing = prefs.getBoolean(
            com.abang.prayerzones.util.InMosqueModeManager.PREF_SHOW_STAY_CARD, false
        )

        if (!showing) {
            _showStayCard.value = false
            return
        }

        val now = System.currentTimeMillis()

        // Use the posted-at timestamp for expiry (more reliable than windowEnd which gets cleared)
        val postedAt = prefs.getLong("in_mosque_stay_card_posted_at", 0L)
        if (postedAt > 0L) {
            // Auto-dismiss 10 minutes after the card was posted
            val cardAgeMs = now - postedAt
            if (cardAgeMs > 10 * 60 * 1000L) {
                Log.d("MosqueDetailVM", "Stay card expired: postedAt=$postedAt, age=${cardAgeMs / 1000}s — auto-dismissing")
                _showStayCard.value = false
                prefs.edit()
                    .putBoolean(com.abang.prayerzones.util.InMosqueModeManager.PREF_SHOW_STAY_CARD, false)
                    .apply()
                return
            }
        }

        // Stay Card is "sticky": only dismiss via user "OK", 10-min expiry above,
        // or distance check (handled in InMosqueModeManager.handleLocationResult).
        // Do NOT auto-dismiss based on windowEnd/isActive state — those are cleared
        // during window transition while the card should remain visible.

        // Card is valid, show it
        _showStayCard.value = true
        Log.d("MosqueDetailVM", "Stay card is valid — showing in UI")
    }

    private fun setupNotifications() {
        viewModelScope.launch {
            try {
                notificationHelper.createPrayerNotificationChannel()
                notificationHelper.createAdhanNotificationChannel()

                try {
                    if (permissionHelper.canScheduleExactAlarms()) {
                        setupAlarms()
                    }
                } catch (_: Throwable) {
                    // ignore permission check failures
                }
            } catch (e: Exception) {
                Log.e("MosqueDetailViewModel", "Error setting up notifications", e)
            }
        }
    }

    private fun setupAlarms() {
        val currentMosque = _mosque ?: return
        viewModelScope.launch {
            try {
                // ✅ Use mosque's canonical time zone
                val zoneId = try {
                    ZoneId.of(currentMosque.timeZone)
                } catch (e: Exception) {
                    val msg = "Invalid TimeZone Configuration: '${currentMosque.timeZone}'"
                    Log.e("MosqueDetailVM", msg, e)
                    _configurationError.value = msg
                    return@launch
                }

                val nowInMosqueZone = try {
                    currentMosque.getCurrentLocalTime()
                } catch (e: Exception) {
                    val msg = "Invalid TimeZone Configuration: '${currentMosque.timeZone}'"
                    Log.e("MosqueDetailVM", msg, e)
                    _configurationError.value = msg
                    return@launch
                }

                val todayInMosqueZone = nowInMosqueZone.toLocalDate()
                val isFriday = todayInMosqueZone.dayOfWeek == DayOfWeek.FRIDAY

                // Get phone's current time for logging comparison
                val nowInPhoneZone = ZonedDateTime.now()

                Log.d("MosqueDetailVM", """
                    ════════════════════════════════════════════════════════════
                    SETUP ALARMS FOR: ${currentMosque.name}
                    ────────────────────────────────────────────────────────────
                    Mosque Timezone: ${currentMosque.timeZone}
                    Mosque Current Time: ${nowInMosqueZone.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}
                    Phone Timezone: ${ZoneId.systemDefault()}
                    Phone Current Time: ${nowInPhoneZone.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}
                    Is First Mosque: $isFirstMosque (${if (isFirstMosque) "FULL AZAN" else "TONE ONLY"})
                    Is Friday: $isFriday
                    ════════════════════════════════════════════════════════════
                """.trimIndent())

                // Use ONLY the filtered prayer times
                val filteredPrayers = _prayerTimes.value

                // Prepare timings for scheduling (with Jumuah on Friday)
                val timingsForScheduling = if (isFriday) {
                    applyFridayModeToTimings(filteredPrayers, currentMosque)
                } else {
                    filteredPrayers
                }

                val muteSettings = _muteStates.value

                // Schedule alarms ONLY for filtered prayers
                timingsForScheduling.forEach { (prayerName, timeStr) ->
                    // Final safety check: NEVER schedule excluded prayers
                    if (PrayerFilter.isExcluded(prayerName)) {
                        Log.w("MosqueDetailVM", "⊘ Attempted to schedule excluded prayer: $prayerName")
                        return@forEach
                    }

                    if (muteSettings[keyFor(currentMosque.id, prayerName)] != true) {
                        try {
                            val time = LocalTime.parse(timeStr.take(5))

                            // ✅ Use mosque's date (todayInMosqueZone), not phone's date
                            var alarmTime = ZonedDateTime.of(todayInMosqueZone, time, zoneId)

                            // ✅ Compare with mosque's current time (not phone's time)
                            if (alarmTime.isBefore(nowInMosqueZone)) {
                                alarmTime = alarmTime.plusDays(1)
                            }

                            // Convert to UTC epoch for AlarmManager
                            val utcEpochMillis = alarmTime.toInstant().toEpochMilli()

                            Log.d("MosqueDetailVM", """
                                ┌─ Scheduling: $prayerName
                                ├─ Mosque Time: ${alarmTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))}
                                ├─ UTC Epoch: $utcEpochMillis
                                ├─ Phone Time Equivalent: ${alarmTime.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))}
                                └─ Audio: ${if (isFirstMosque) "FULL AZAN" else "NOTIFICATION TONE"}
                            """.trimIndent())

                            alarmScheduler.schedulePrayerAlarmIfMissing(
                                mosque = currentMosque,
                                prayerName = prayerName,
                                prayerTime = alarmTime,
                                isFirstMosque = isFirstMosque
                            )
                        } catch (pe: Exception) {
                            Log.w("MosqueDetailVM", "⚠️ Skipping invalid time for $prayerName: $timeStr", pe)
                        }
                    } else {
                        Log.d("MosqueDetailVM", "🔇 Skipping muted prayer: $prayerName")
                    }
                }

                Log.d("MosqueDetailVM", "✅ Alarm setup completed for ${currentMosque.name}")
            } catch (e: Exception) {
                Log.e("MosqueDetailVM", "❌ Error setting up alarms", e)
            }
        }
    }

    /**
     * Slot-0-exclusive Friday split conditions:
     * - slot0 (isFirstMosque)
     * - local (mosque offset == device offset at "now")
     * - Friday
     * - time mismatch (jumu'ah1 != dhuhr)
     */
    private fun shouldSplitLocalFriday(nowInMosqueZone: ZonedDateTime, dhuhrTime: String, jumuah1Time: String): Boolean {
        if (!isFirstMosque) return false
        if (nowInMosqueZone.dayOfWeek != DayOfWeek.FRIDAY) return false

        // "Local" means same offset right now (handles DST).
        val deviceOffset = ZonedDateTime.now(ZoneId.systemDefault()).offset
        val mosqueOffset = nowInMosqueZone.offset
        if (deviceOffset != mosqueOffset) return false

        return dhuhrTime.trim().take(5) != jumuah1Time.trim().take(5)
    }

    private fun isPrioritizeJumuahEnabled(): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("pref_prioritize_jumuah", true)
    }

    private fun applyFridayModeToTimings(
        baseTimings: Map<String, String>,
        mosque: Mosque
    ): Map<String, String> {
        val result = baseTimings.toMutableMap()
        val dhuhrTime = result["Dhuhr"] ?: return result

        // IMPORTANT: Do NOT create extra alarms for Dhuhr/Jumu'ah split.
        // For all slots and all conditions, scheduling stays as a single event at Jumu'ah time.
        val jumuah1Time = mosque.jumuah?.session1 ?: dhuhrTime
        val jumuah2Time = mosque.jumuah?.session2

        // Keep Dhuhr as-is for normal daily logic; add a single Jumu'ah event.
        result["Jumu'ah"] = jumuah1Time
        if (jumuah2Time != null) result["Jumu'ah 2"] = jumuah2Time
        return result
    }

    private fun startTimers() {
        val currentMosque = _mosque ?: return

        // Cancel any existing timer job before starting a new one
        timerJob?.cancel()

        // Start new timer and store the job reference
        timerJob = viewModelScope.launch(Dispatchers.Default) {
            val zoneId = try {
                ZoneId.of(currentMosque.timeZone)
            } catch (e: Exception) {
                val msg = "Invalid TimeZone Configuration: '${currentMosque.timeZone}'"
                Log.e("MosqueDetailVM", msg, e)
                _configurationError.value = msg
                // Emit an error row so UI can show a card instead of crashing
                _prayerUiState.value = listOf(
                    PrayerUiState(
                        prayerKey = "ERROR",
                        displayName = "Invalid TimeZone",
                        time = "--:--",
                        isNext = false,
                        type = PrayerType.SECONDARY,
                        errorMessage = msg
                    )
                )
                return@launch
            }


            while (isActive) {
                val now = ZonedDateTime.now(zoneId)
                val today = now.toLocalDate()
                val timings = _prayerTimes.value.toMutableMap()

                // Build displayOrder dynamically: add Jumuah on Fridays
                val dhuhrTime = timings["Dhuhr"]
                val jumuah1Time = currentMosque.jumuah?.session1 ?: dhuhrTime

                val splitLocalFriday = if (dhuhrTime != null && jumuah1Time != null) {
                    shouldSplitLocalFriday(now, dhuhrTime, jumuah1Time)
                } else {
                    false
                }

                val displayOrder = if (now.dayOfWeek == DayOfWeek.FRIDAY) {
                    injectFridayRows(timings, currentMosque, splitLocalFriday)
                } else {
                    PrayerFilter.getDisplayOrder()
                }

                var prayerEventsToday = displayOrder.mapNotNull { prayerKey ->
                    timings[prayerKey]?.let { timeStr ->
                        try {
                            ZonedDateTime.of(today, LocalTime.parse(timeStr.take(5)), zoneId).let { prayerKey to it }
                        } catch (_: Exception) { null }
                    }
                }

                // Duha is already in the prayerEventsToday from filtered timings (no need to inject from Sunrise)

                val nextPrayer = getNextPrayerAndSeconds(prayerEventsToday, zoneId)
                _nextPrayerInfo.value = nextPrayer

                if (nextPrayer != null && nextPrayer.secondsUntil == 0L) {
                    val prayerKey = nextPrayer.name

                    // CRITICAL: Filter out Sunrise and Sunset - they should never be "next prayer"
                    if (prayerKey == "Sunrise" || prayerKey == "Sunset") {
                        Log.d("MosqueDetailVM", "[${currentMosque.name}] $prayerKey reached but skipping notification/audio (reference only)")
                    } else {
                        val uniqueKey = keyFor(currentMosque.id, prayerKey)

                        _activePrayer.value = ActivePrayerState(
                            mosqueId = currentMosque.id,  // ✅ ADD THIS
                            prayerKey = prayerKey,
                            startTimeMillis = System.currentTimeMillis()
                        )

                        if (_muteStates.value[uniqueKey] != true) {
                            viewModelScope.launch(Dispatchers.IO) prayerAudio@{
                                try {
                                    if (isFirstMosque) {
                                        // ✅ Unify: full azan playback is owned by AzanPlaybackService.
                                        // Determine priority for Friday split modes
                                        val isSecondaryLocalFridayEvent = splitLocalFriday && when (prayerKey) {
                                            "Jumu'ah 1" -> !isPrioritizeJumuahEnabled()
                                            "Dhuhr" -> isPrioritizeJumuahEnabled()
                                            else -> false
                                        }

                                        if (isSecondaryLocalFridayEvent) {
                                            runCatching { MediaPlayerManager.playNotificationTone(context) }
                                                .onFailure { Log.e("MosqueDetailVM", "Secondary Friday tone failed", it) }
                                            return@prayerAudio
                                        }

                                        // 🚫 CHECK AUDIO STYLE PREFERENCE - Respect user's choice
                                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                        val audioStyle = prefs.getString("pref_main_slot_audio_style", "azan")
                                        val isDuha = prayerKey.equals("Duha", ignoreCase = true)

                                        Log.d("MosqueDetailVM", "Slot 0 Timer: Prayer=$prayerKey, AudioStyle=[$audioStyle], IsDuha=$isDuha")

                                        // Handle based on audio style
                                        when (audioStyle) {
                                            "tone" -> {
                                                // User wants tone - play selected tone (except Duha uses duha_ar)
                                                if (isDuha) {
                                                    // Duha exception: always use service for duha_ar.mp3
                                                    Log.d("MosqueDetailVM", "Duha exception: starting service for duha_ar.mp3")
                                                } else {
                                                    // Play selected tone
                                                    val selectedTone = prefs.getString("pref_main_slot_tone", "tone1") ?: "tone1"
                                                    runCatching { TonePlayer.playSelectedTone(context, selectedTone) }
                                                        .onFailure { Log.e("MosqueDetailVM", "Tone playback failed", it) }
                                                    return@prayerAudio // Don't start Azan service
                                                }
                                            }
                                            "tts" -> {
                                                // User wants TTS - start TTS service instead
                                                Log.d("MosqueDetailVM", "TTS mode: TODO - start TTS service")
                                                // TODO: Start TTSAnnouncementService here if needed
                                                return@prayerAudio // Don't start Azan service
                                            }
                                            else -> {
                                                // "azan" or default - continue to Azan service below
                                                Log.d("MosqueDetailVM", "Azan mode: continuing to service start")
                                            }
                                        }

                                        val soundResId = when (prayerKey) {
                                            "Fajr" -> R.raw.morning1
                                            "Dhuhr", "Asr", "Maghrib", "Isha", "Jumu'ah", "Jumu'ah 1", "Jumu'ah1" -> R.raw.azan
                                            "Duha" -> R.raw.duha_ar
                                            "Jumu'ah 2", "Jumu'ah2" -> R.raw.jumua2
                                            else -> 0
                                        }

                                        val timeStr = ZonedDateTime.now(ZoneId.of(currentMosque.timeZone))
                                            .format(DateTimeFormatter.ofPattern("HH:mm"))

                                        if (soundResId != 0) {
                                            val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
                                                putExtra(AzanPlaybackService.EXTRA_PRAYER_NAME, prayerKey)
                                                putExtra(AzanPlaybackService.EXTRA_SOUND_RES_ID, soundResId)
                                                putExtra(AzanPlaybackService.EXTRA_PRAYER_TIME, timeStr)
                                                putExtra(AzanPlaybackService.EXTRA_MOSQUE_ID, currentMosque.id)
                                                putExtra(AzanPlaybackService.EXTRA_MOSQUE_NAME, currentMosque.name)
                                            }

                                            try {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    context.startForegroundService(serviceIntent)
                                                } else {
                                                    context.startService(serviceIntent)
                                                }

                                                // UI card follows service lifetime best-effort
                                                _nowPlayingAzan.value = prayerKey
                                            } catch (e: Exception) {
                                                Log.e("MosqueDetailVM", "Failed to start AzanPlaybackService", e)
                                            }
                                        } else {
                                            Log.w("MosqueDetailVM", "No azan mapped for '$prayerKey' - skipping")
                                        }
                                    } else {
                                        // Secondary mosques: route through NotificationQueue.
                                        // This is the ONLY audio dispatch path — same as AlarmReceiver.
                                        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                                        val secondaryAudioMode = prefs.getString("pref_secondary_audio_mode", "tts") ?: "tts"
                                        val isTTSEnabled = secondaryAudioMode == "tts" || secondaryAudioMode == "tone_tts"

                                        val tonePrefValue: String? = when (secondaryAudioMode) {
                                            "tone", "tone_tts" -> prefs.getString("pref_secondary_tone", "tone1") ?: "tone1"
                                            else -> null
                                        }

                                        val ttsIntent: android.content.Intent? = if (isTTSEnabled) {
                                            android.content.Intent(context, com.abang.prayerzones.service.TTSAnnouncementService::class.java).apply {
                                                putExtra(com.abang.prayerzones.service.TTSAnnouncementService.EXTRA_PRAYER_NAME, prayerKey)
                                                putExtra(com.abang.prayerzones.service.TTSAnnouncementService.EXTRA_MOSQUE_NAME, currentMosque.name)
                                                // ✅ FIX: Use actual slot index (pager page) instead of hardcoded 1.
                                                // Hardcoding 1 caused every mosque's TTS notification to navigate
                                                // to Slot 1, regardless of which slot fired the prayer.
                                                putExtra("slot_index", _slotIndex)
                                            }
                                        } else null

                                        // Write the dedup lock so that when AlarmManager fires
                                        // ~1 s later for the same prayer, the receiver blocks it.
                                        val dedupZone = runCatching { java.time.ZoneId.of(currentMosque.timeZone) }
                                            .getOrElse { java.time.ZoneId.of("UTC") }
                                        val dedupDate = java.time.ZonedDateTime.now(dedupZone)
                                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                        val dedupKey = "fired_${currentMosque.id}_${prayerKey}_${dedupDate}"
                                        context.applicationContext
                                            .getSharedPreferences("prayer_trigger_history", android.content.Context.MODE_PRIVATE)
                                            .edit().putLong(dedupKey, System.currentTimeMillis()).apply()
                                        Log.d("MosqueDetailVM", "🔒 DEDUP lock written: $dedupKey")

                                        com.abang.prayerzones.util.NotificationQueue.enqueue(
                                            com.abang.prayerzones.util.NotificationQueue.QueueItem.SecondaryNotification(
                                                context       = context.applicationContext,
                                                tonePrefValue = tonePrefValue,
                                                ttsIntent     = ttsIntent,
                                                isTTSEnabled  = isTTSEnabled,
                                                label         = "${currentMosque.name}/$prayerKey",
                                                prayerName    = prayerKey,
                                                mosqueName    = currentMosque.name,
                                                slotIndex     = _slotIndex
                                            )
                                        )
                                        Log.d("MosqueDetailVM", "⏳ Enqueued secondary: ${currentMosque.name}/$prayerKey tone=$tonePrefValue tts=$isTTSEnabled")
                                    }
                                } catch (e: Exception) {
                                    Log.e("MosqueDetailVM", "Error handling prayer $prayerKey", e)
                                }
                            }
                        }
                    }
                }

                // Build UI state (Sunrise already filtered by PrayerFilter)
                val nowForUi = ZonedDateTime.now(zoneId)

                // Get time format preference
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val use24Hour = prefs.getBoolean("pref_24_hour_format", true)
                val timePattern = if (use24Hour) "HH:mm" else "h:mm a"

                _prayerUiState.value = prayerEventsToday.map { (prayerKey, prayerZdt) ->
                    val isNext = prayerKey == nextPrayer?.name

                    val prayerType = when (prayerKey) {
                        "Duha" -> PrayerType.SECONDARY

                        // Friday sessions:
                        // - Session 1 behaves like Dhuhr replacement: PRIMARY by default.
                        // - Session 2 is always SECONDARY.
                        "Jumu'ah" -> PrayerType.PRIMARY
                        "Jumu'ah 1" -> {
                            if (splitLocalFriday && !isPrioritizeJumuahEnabled()) PrayerType.SECONDARY else PrayerType.PRIMARY
                        }
                        "Jumu'ah 2" -> PrayerType.SECONDARY

                        "Dhuhr" -> {
                            if (splitLocalFriday && isPrioritizeJumuahEnabled()) PrayerType.SECONDARY else PrayerType.PRIMARY
                        }

                        else -> PrayerType.PRIMARY
                    }

                    val displayName = prayerKey

                    // ✅ Active window computed from mosque-local time to survive lock/background.
                    val isActive = LiveWindow.isWithinLiveWindow(
                        prayerTime = prayerZdt.format(DateTimeFormatter.ofPattern("HH:mm")),
                        now = nowForUi,
                        windowMinutes = 20L
                    )

                    PrayerUiState(
                        prayerKey = prayerKey,
                        displayName = displayName,
                        time = prayerZdt.format(DateTimeFormatter.ofPattern(timePattern)),
                        isNext = isNext,
                        type = prayerType,
                        isActive = isActive,
                        isApproximate = _isApproximateTiming.value,
                        errorMessage = null
                    )
                }

                val configError = _configurationError.value
                if (configError != null) {
                    _prayerUiState.value = listOf(
                        PrayerUiState(
                            prayerKey = "ERROR",
                            displayName = "Invalid TimeZone",
                            time = "--:--",
                            isNext = false,
                            type = PrayerType.SECONDARY,
                            errorMessage = configError
                        )
                    )
                    delay(1000L)
                    continue
                }


                val countdownFormatted = nextPrayer?.secondsUntil?.let { formatSecondsToDuration(it) } ?: "--:--"
                val localTimeFormatted = now.format(DateTimeFormatter.ofPattern(timePattern))
                _countdownStrings.value = countdownFormatted to "LT $localTimeFormatted"

                // Update ticker every second to force UI recomposition for active prayer & in-mosque state
                _currentTimestamp.value = System.currentTimeMillis()

                // Sync stay-card state from SharedPreferences every ~30 seconds (for Slot 0 only)
                if (isFirstMosque && (_currentTimestamp.value / 1000) % 30 == 0L) {
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    val cardShowing = prefs.getBoolean(
                        com.abang.prayerzones.util.InMosqueModeManager.PREF_SHOW_STAY_CARD, false
                    )
                    if (_showStayCard.value != cardShowing) {
                        _showStayCard.value = cardShowing
                    }
                }

                delay(1000L)
            }
        }

        Log.d("MosqueDetailVM", "[${currentMosque.name}] Timer started")
    }

    private fun injectFridayRows(
        timings: MutableMap<String, String>,
        mosque: Mosque,
        splitLocalFriday: Boolean
    ): List<String> {
        val dhuhrTime = timings["Dhuhr"] ?: return PrayerFilter.getDisplayOrder()
        val jumuah1Time = mosque.jumuah?.session1 ?: dhuhrTime
        val jumuah2Time = mosque.jumuah?.session2

        val order = mutableListOf("Fajr", "Duha")

        if (splitLocalFriday) {
            val prioritizeJumuah = isPrioritizeJumuahEnabled()
            if (prioritizeJumuah) {
                timings["Jumu'ah 1"] = jumuah1Time
                timings["Dhuhr"] = dhuhrTime
                order += "Jumu'ah 1"
                order += "Dhuhr"
            } else {
                timings["Dhuhr"] = dhuhrTime
                timings["Jumu'ah 1"] = jumuah1Time
                order += "Dhuhr"
                order += "Jumu'ah 1"
            }
        } else {
            // Single-row fallback for all other cases (including distant mosques)
            val label = if (jumuah2Time != null) "Jumu'ah 1" else "Jumu'ah"
            timings[label] = jumuah1Time
            order += label
        }

        if (jumuah2Time != null) {
            timings["Jumu'ah 2"] = jumuah2Time
            order += "Jumu'ah 2"
        }

        order += listOf("Asr", "Maghrib", "Isha")
        return order
    }

    private fun formatSecondsToDuration(totalSeconds: Long): String {
        if (totalSeconds < 0) return "0m 0s"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    private fun fetchAllPrayerTimes() {
        val currentMosque = _mosque ?: return
        viewModelScope.launch {
            try {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val debugMode = prefs.getBoolean("DEBUG_MODE_ENABLED", false)

                // 🔧 DEBUG MODE: Short-circuit to defaultTimings and never overwrite from cache/network.
                if (debugMode && currentMosque.defaultTimings?.isNotEmpty() == true) {
                    Log.w("MosqueDetailVM", "🔧 DEBUG MODE: Short-circuiting to defaultTimings for ${currentMosque.id} (${currentMosque.name})")

                    val normalized = PRAYER_ORDER.mapNotNull { key ->
                        val v = currentMosque.defaultTimings[key]?.trim().orEmpty()
                        if (v.isBlank()) return@mapNotNull null
                        try {
                            LocalTime.parse(v.take(5), TIME_FMT)
                            key to v.take(5)
                        } catch (e: Exception) {
                            Log.w("MosqueDetailVM", "Invalid format for $key: '$v' in ${currentMosque.id}")
                            null
                        }
                    }.toMap()

                    _prayerTimes.value = normalized
                    _isApproximateTiming.value = true

                    Log.w("MosqueDetailVM", "🔧 DEBUG MODE final _prayerTimes for ${currentMosque.id}: $normalized")
                    return@launch
                }

                // ---------------- Normal Mode (existing behavior) ----------------

                // STEP 1: Instant display with default timings (0ms delay) - ALREADY FILTERED
                currentMosque.defaultTimings?.let { defaults ->
                    _prayerTimes.value = PrayerFilter.filterToMap(defaults)
                    _isApproximateTiming.value = true
                    Log.d("MosqueDetailVM", "[${currentMosque.name}] Using default timings (instant, filtered)")
                }

                // STEP 2: Check memory cache first (instant)
                val memoryCache = repository.getCachedPrayerTimes(currentMosque.id)
                if (memoryCache != null) {
                    val filtered = PrayerFilter.filterToMap(memoryCache.data.timings)
                    _prayerTimes.value = filtered
                    _isApproximateTiming.value = false
                    Log.d("MosqueDetailVM", "[${currentMosque.name}] Using memory cache (instant, filtered)")
                } else {
                    val diskCache = cache.loadCachedPrayerTimes(currentMosque.id)
                    if (diskCache != null) {
                        val filtered = PrayerFilter.filterToMap(diskCache.timings)
                        _prayerTimes.value = filtered
                        _isApproximateTiming.value = false
                        Log.d("MosqueDetailVM", "[${currentMosque.name}] Using disk cache (filtered)")
                    }
                }

                // STEP 3: Fetch fresh data in background (non-blocking)
                launch {
                    try {
                        Log.d("MosqueDetailVM", "[${currentMosque.name}] Fetching fresh data in background...")
                        val freshResponse = repository.getPrayerTimes(currentMosque)

                        // ✅ Persist API timezone into mosque so ALL future calculations are correct
                        val apiTimeZone = freshResponse.data.meta.timezone
                        if (apiTimeZone.isNotBlank()) {
                            // Update active in-VM instance
                            if (apiTimeZone != _mosque?.timeZone) {
                                _mosque = _mosque?.copy(timeZone = apiTimeZone)
                                Log.d("MosqueDetailVM", "[${currentMosque.name}] Updated mosque timeZone from API: $apiTimeZone")
                                updateAzanEligibility()
                                updateDateString()
                            }

                            // Persist into master repository for cold starts / alarm surgery installs
                            mosqueRepository.updateMosqueTimeZone(currentMosque.id, apiTimeZone)
                        }

                        val freshTimings = PrayerFilter.filterToMap(freshResponse.data.timings)

                        if (freshTimings != _prayerTimes.value) {
                            _prayerTimes.value = freshTimings
                            cache.saveTimings(currentMosque.id, freshResponse.data.timings)
                            _isApproximateTiming.value = false
                            Log.d("MosqueDetailVM", "[${currentMosque.name}] Updated with fresh filtered data")
                        } else {
                            Log.d("MosqueDetailVM", "[${currentMosque.name}] Fresh data matches cache")
                        }
                    } catch (e: Exception) {
                        Log.e("MosqueDetailVM", "[${currentMosque.name}] Background fetch failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("MosqueDetailVM", "Error fetching prayer times", e)
            }
        }
    }

    private fun updatePrayerTimes(response: PrayerResponse) {
        _prayerTimes.value = response.data.timings
    }

    private fun getNextPrayerAndSeconds(prayerEvents: List<Pair<String, ZonedDateTime>>, zoneId: ZoneId): NextPrayerInfo? {
        if (prayerEvents.isEmpty()) return null

        val now = ZonedDateTime.now(zoneId)
        // Filter out Sunrise and Sunset - they should never be "next prayer"
        val validPrayerCandidates = prayerEvents.filterNot { it.first == "Sunrise" || it.first == "Sunset" }

        val nextPrayerToday = validPrayerCandidates.firstOrNull { it.second.isAfter(now) }

        val finalPrayerInfo = if (nextPrayerToday != null) {
            nextPrayerToday
        } else {
            validPrayerCandidates.firstOrNull()?.let { it.first to it.second.plusDays(1) } ?: return null
        }

        val secondsUntil = Duration.between(now, finalPrayerInfo.second).seconds
        return NextPrayerInfo(finalPrayerInfo.first, secondsUntil)
    }

    fun stopAzan() {
        // Stop playback at the source (service) so the foreground notification goes away too.
        runCatching {
            val stopIntent = Intent(context, AzanPlaybackService::class.java).apply {
                action = "STOP_AZAN"
            }
            context.startService(stopIntent)
        }.onFailure {
            Log.e("MosqueDetailVM", "Failed to send STOP_AZAN to service", it)
        }

        MediaPlayerManager.stop()
        _nowPlayingAzan.value = null
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        MediaPlayerManager.stop()
        Log.d("MosqueDetailVM", "ViewModel cleared, timer cancelled")
    }

    // ---------------------------------------------------------------------
    // Restored helpers (were present before Friday-mode refactor)
    // ---------------------------------------------------------------------

    private fun keyFor(mosqueId: String, prayerKey: String): String = "$mosqueId|$prayerKey"

    fun toggleMute(mosqueId: String, prayerKey: String) {
        val key = keyFor(mosqueId, prayerKey)
        val current = _muteStates.value[key] ?: false
        val newState = !current

        _muteStates.value = _muteStates.value.toMutableMap().apply {
            put(key, newState)
        }

        // Persist
        viewModelScope.launch {
            runCatching { mosqueRepository.setPrayerMuted(mosqueId, prayerKey, newState) }
                .onFailure { Log.e("MosqueDetailVM", "Failed to persist mute state", it) }
        }

        if (newState && _nowPlayingAzan.value == prayerKey) {
            stopAzan()
        }
    }

    private fun updateAzanEligibility() {
        val currentMosque = _mosque ?: return
        _azanEligibility.value = runCatching {
            ZoneId.of(currentMosque.timeZone) == ZoneId.systemDefault()
        }.getOrDefault(false)
    }

    private fun updateDateString() {
        _dateString.value = getDateFor()
    }

    fun getDateFor(): String {
        val currentMosque = _mosque ?: return ""
        return try {
            val zoneId = ZoneId.of(currentMosque.timeZone)
            val gregorian = LocalDate.now(zoneId)
                .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
            val hijri = HijrahDate.from(LocalDate.now(zoneId))
            val day = hijri.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
            val monthIndex = hijri.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) - 1
            val hijriMonths = listOf(
                "Muharr", "Safar", "Rabi-1", "Rabi-2", "Jumad-1", "Jumad-2",
                "Rajab", "Sha'bn", "Ramadh", "Shawwl", "DhuQa", "DhuHi"
            )
            "$gregorian | $day ${hijriMonths.getOrNull(monthIndex) ?: ""} ${hijri.get(java.time.temporal.ChronoField.YEAR)}"
        } catch (_: Exception) {
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
    }

    /**
     * Forces an immediate UI refresh tick (used onResume after unlock) so PrayerUiState.isActive
     * is recalculated right away. Also syncs the stay-card state.
     */
    fun forceRefreshTicker() {
        _currentTimestamp.value = System.currentTimeMillis()
        // Also re-check stay card validity on resume (Slot 0 only)
        if (_isFirstMosque == true) {
            performStayCardSanityCheck()
        }
    }
}
