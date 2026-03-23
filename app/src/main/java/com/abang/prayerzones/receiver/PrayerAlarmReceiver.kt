package com.abang.prayerzones.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import com.abang.prayerzones.R
import com.abang.prayerzones.data.mosquePreferencesDataStore
import com.abang.prayerzones.service.AzanPlaybackService
import com.abang.prayerzones.service.TTSAnnouncementService
import com.abang.prayerzones.util.AlarmScheduler
import com.abang.prayerzones.util.AlarmSurgeryManager
import com.abang.prayerzones.util.NotificationQueue
import com.abang.prayerzones.util.PlaybackState
import com.abang.prayerzones.util.PrayerFilter
import com.abang.prayerzones.util.TonePlayer
import com.abang.prayerzones.util.TTSAnnouncementHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

@AndroidEntryPoint
class PrayerAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmSurgeryManager: AlarmSurgeryManager
    @Inject lateinit var alarmScheduler: AlarmScheduler

    companion object {
        private const val TAG = "PrayerAlarmReceiver"
        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_PRAYER_TIME = "prayer_time"
        const val EXTRA_MOSQUE_NAME = "mosque_name"
        const val EXTRA_IS_FIRST_MOSQUE = "is_first_mosque"
        const val EXTRA_MOSQUE_ID = "mosque_id"
        const val EXTRA_MOSQUE_TIMEZONE = "mosque_timezone"
        const val EXTRA_TARGET_MILLIS = "target_millis"
        private const val WAKE_LOCK_TIMEOUT = 10_000L // 10 seconds for receiver

        private const val SHORT_TONE_MAX_MS = 2_000L
        private const val SECONDARY_TTS_DELAY_MS = 1_500L
        private const val TRIGGER_TOLERANCE_MINUTES = 1L

        // ── Idempotent dedup lock ────────────────────────────────────────────
        // SharedPreferences file that persists event signatures across process deaths.
        // In-memory debounce (5s) was not sufficient because Android can cold-start a
        // new process for each zombie alarm, resetting all in-memory state.
        private const val DEDUP_PREFS_NAME  = "prayer_trigger_history"
        private const val DEDUP_WINDOW_MS   = 55_000L   // 55 s — stays within one clock minute
        private const val DEDUP_TTL_MS      = 48L * 60 * 60 * 1000 // 48 h retention
    }

    override fun onReceive(context: Context, intent: Intent) {
        val wakeLock = acquireWakeLock(context)

        try {
            Log.d("TTS_TEST", "PrayerAlarmReceiver onReceive() action=${intent.action}")

            // Handle midnight refresh first
            if (intent.action == AlarmScheduler.ACTION_MIDNIGHT_REFRESH) {
                val slotIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT_INDEX, 0)
                val mosqueId = intent.getStringExtra(EXTRA_MOSQUE_ID).orEmpty()
                val tz = intent.getStringExtra(EXTRA_MOSQUE_TIMEZONE).orEmpty()

                Log.i(TAG, "Midnight refresh received slot=$slotIndex mosqueId=$mosqueId tz=$tz")

                // Housekeeping: prune dedup history older than 48 h
                pruneStaleDedupEntries(context)

                // Reinstall alarms for the new day (all active mosques) and re-arm next midnight.
                alarmSurgeryManager.installActiveAlarms()
                if (mosqueId.isNotBlank() && tz.isNotBlank()) {
                    alarmScheduler.scheduleMidnightRefresh(slotIndex, mosqueId, tz)
                }
                return
            }

            val prayerName = intent.getStringExtra(EXTRA_PRAYER_NAME) ?: return
            val prayerTime = intent.getStringExtra(EXTRA_PRAYER_TIME) ?: return
            val mosqueName = intent.getStringExtra(EXTRA_MOSQUE_NAME) ?: ""
            val mosqueId = intent.getStringExtra(EXTRA_MOSQUE_ID) ?: ""
            val isFirstMosque = intent.getBooleanExtra(EXTRA_IS_FIRST_MOSQUE, false)

            // SharedPreferences master/style toggles (unified storage)
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

            // ✅ DEBUG MODE: Bypass strict guards for test mosques
            // isDebugMosque = true only when DEBUG_MODE_ENABLED is set AND mosqueId is a known
            // test ID. These mosques are NOT stored in DataStore slots (they are injected at
            // runtime by MosqueRepository), so the DataStore slot validation would always block
            // them. The TZ guard and Ghost Alarm guard are also bypassed because mock prayer
            // times are artificially generated and do not match real-world clock times.
            val isDebug = prefs.getBoolean("DEBUG_MODE_ENABLED", false)
            val debugIds = listOf("SG009", "SA009")
            val isDebugMosque = isDebug && debugIds.contains(mosqueId)

            // ✅ ACTIVE SLOT GUARD: Prevent ghost mosque announcements
            // FAIL-SILENT: If mosque is not in active slots OR read fails, ABORT immediately
            // ⚠️ DEBUG BYPASS: Test mosques skip this because they are not persisted in DataStore slots
            if (mosqueId.isNotBlank() && !isDebugMosque) {
                val activeSlotIds: Set<String> = try {
                    runBlocking {
                        val dataStore = context.mosquePreferencesDataStore
                        val prefs = dataStore.data.first()
                        (0..3).mapNotNull { slot ->
                            val key = stringPreferencesKey("mosque_slot_$slot")
                            prefs[key]
                        }.toSet()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ ABORT: Failed to read active slots from DataStore", e)
                    return // FAIL-SILENT: Do not proceed if we can't verify slots
                }

                // Strict validation: empty slots = no active mosques = abort all alarms
                if (activeSlotIds.isEmpty()) {
                    Log.w(TAG, "🚫 ABORT: Active slots are empty, blocking all alarms")
                    return
                }

                if (!activeSlotIds.contains(mosqueId)) {
                    Log.w(TAG, "🚫 Ghost alarm blocked: mosqueId=$mosqueId not in active slots. Active=$activeSlotIds")
                    return
                }

                Log.d(TAG, "✅ Active slot validation passed: mosqueId=$mosqueId in slots=$activeSlotIds")
            } else if (isDebugMosque) {
                Log.w(TAG, "⚠️ DEBUG: Bypassing Active Slot Validation for test mosque: $mosqueId")
            }
            val slot0MasterEnabled = prefs.getBoolean("pref_notifications_enabled", true)
            val globalMuteDuha = prefs.getBoolean("pref_mute_duha", false)
            val mainSlotAudioStyle = prefs.getString("pref_main_slot_audio_style", "azan") ?: "azan"

            // Global Duha mute override (must override everything)
            if (globalMuteDuha && prayerName == "Duha") {
                Log.d(TAG, "Global Duha mute is ON; skipping trigger for Duha")
                return
            }

            // ✅ Time zone awareness at trigger time (prevents phone-local leakage)
            // HARD ABORT: if the timezone extra is missing or invalid, do NOT fall back to
            // device local time — that would make a distant mosque fire at the wrong instant.
            val mosqueTimeZone = intent.getStringExtra(EXTRA_MOSQUE_TIMEZONE)
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.e(TAG, "🚫 ABORT: EXTRA_MOSQUE_TIMEZONE missing for $prayerName at $mosqueName. Cannot safely determine trigger time.")
                    return
                }

            val zoneId = runCatching { ZoneId.of(mosqueTimeZone) }.getOrElse {
                Log.e(TAG, "🚫 ABORT: Invalid EXTRA_MOSQUE_TIMEZONE='$mosqueTimeZone' for $prayerName at $mosqueName. Fix mosque.timeZone.")
                return
            }
            val nowInMosqueZone = ZonedDateTime.now(zoneId)

            // ✅ TIMEZONE GUARD: Compare mosque's LOCAL time to scheduled prayer time
            // This ensures we only trigger when the mosque's clock matches the prayer time
            // ⚠️ DEBUG BYPASS: Test mosques (SG009, SA009) skip this validation
            if (!isDebugMosque) {
                val scheduledHm = prayerTime.trim().take(5) // "HH:mm"
                val currentTimeString = nowInMosqueZone.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

                // Allow 1-minute tolerance due to AlarmManager delivery jitter
                val scheduled = runCatching {
                    java.time.LocalTime.parse(scheduledHm)
                }.getOrNull()
                val nowTime = nowInMosqueZone.toLocalTime()

                val withinTolerance = if (scheduled != null) {
                    val diff = kotlin.math.abs(java.time.Duration.between(scheduled, nowTime).toMinutes())
                    diff <= TRIGGER_TOLERANCE_MINUTES
                } else {
                    currentTimeString == scheduledHm
                }

                if (!withinTolerance) {
                    Log.w(
                        TAG,
                        "🚫 TZ_GUARD: Blocking fire for $prayerName: Mosque time is $currentTimeString (zone=$zoneId), but prayer scheduled at $scheduledHm"
                    )
                    return
                }

                Log.d(TAG, "✅ TZ guard passed: $prayerName scheduled=$scheduledHm mosqueNow=$currentTimeString zone=$zoneId")
            } else {
                Log.w(TAG, "⚠️ DEBUG: Bypassing TimeZone Check for test mosque: $mosqueId (scheduled=$prayerTime, zone=$zoneId)")
            }

            // Debug: read and log mode
            val modeFromPrefs = prefs.getString("pref_secondary_audio_mode", "tts") ?: "tts"
            Log.d("TTS_TEST", "AlarmReceiver prefs secondary mode: '$modeFromPrefs'")

            if (PrayerFilter.isExcluded(prayerName)) {
                Log.d(TAG, "BLOCKED: Received alarm for excluded prayer: $prayerName")
                return
            }

            // 🔒 IDEMPOTENT DEDUP LOCK: Prevents zombie/duplicate alarms from firing twice.
            // Uses SharedPreferences so the lock survives process death (unlike volatile fields).
            // Signature: fired_<mosqueId>_<prayerName>_<date-in-mosque-tz>
            // Window: 55 s — stays within one clock minute, covers OEM AlarmManager jitter.
            if (isDuplicateEvent(context, mosqueId, prayerName, mosqueTimeZone)) return

            Log.d(TAG, "Prayer alarm received: $prayerName at $prayerTime, isFirst=$isFirstMosque, mosque=$mosqueName")

            // ✅ Unify notification ownership:
            // - Primary mosque (full azan): ONLY AzanPlaybackService posts the foreground notification.
            // - Secondary mosques (tone/TTS): receiver does not post a persistent notification.
            //   TTSAnnouncementService will show its own foreground notification when speaking.

            // Request audio focus ducking for both primary and secondary.
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusRequest = requestDuckAudioFocus(audioManager)

            if (isFirstMosque) {
                // Slot 0 master gate: strict ON/OFF
                if (!slot0MasterEnabled) {
                    Log.d(TAG, "pref_notifications_enabled=false; skipping Slot 0 trigger")
                    return
                }

                // 🔍 DIAGNOSTIC: Log the exact values read from preferences with brackets
                val selectedTone = prefs.getString("pref_main_slot_tone", "tone1") ?: "tone1"
                Log.i("DEBUG_AUDIO", "Slot 1 Style: [$mainSlotAudioStyle], Tone: [$selectedTone]")

                // 1️⃣ Handle DUHA first (Hard override - always plays duha_ar.mp3)
                if (prayerName.equals("Duha", ignoreCase = true)) {
                    Log.d("DEBUG_AUDIO", "DUHA Override: Ignoring audio style, playing duha_ar.mp3")

                    val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
                        putExtra(AzanPlaybackService.EXTRA_PRAYER_NAME, prayerName)
                        putExtra(AzanPlaybackService.EXTRA_SOUND_RES_ID, R.raw.duha_ar)
                        putExtra(AzanPlaybackService.EXTRA_PRAYER_TIME, prayerTime)
                        putExtra(AzanPlaybackService.EXTRA_MOSQUE_NAME, mosqueName)
                        putExtra(AzanPlaybackService.EXTRA_MOSQUE_ID, intent.getStringExtra(EXTRA_MOSQUE_ID) ?: "")
                        putExtra("slot_index", 0)
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        Log.d(TAG, "✅ Started AzanPlaybackService for Duha (exception)")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to start AzanPlaybackService for Duha", e)
                    }

                    abandonDuckAudioFocusDelayed(audioManager, focusRequest, 1500L)
                    return // 🛑 Stop - Duha handled
                }

                // 2️⃣ Strictly route based on Audio Style (for non-Duha prayers)
                when (mainSlotAudioStyle) {
                    "tone" -> {
                        // SHORT TONE MODE
                        Log.d("DEBUG_AUDIO", "Executing Tone Branch for $prayerName")
                        Log.i("DEBUG_AUDIO", "Playing tone: $selectedTone")

                        val didPlayTone = TonePlayer.playSelectedTone(context, selectedTone)
                        Log.d(TAG, "Tone playback result: $didPlayTone")

                        abandonDuckAudioFocusDelayed(audioManager, focusRequest, SHORT_TONE_MAX_MS)
                        return // 🛑 CRITICAL: Exit here so Azan Service is never called
                    }

                    "tts" -> {
                        // TTS VOICE MODE
                        Log.d("DEBUG_AUDIO", "Executing TTS Branch for $prayerName")

                        val ttsIntent = Intent(context, TTSAnnouncementService::class.java).apply {
                            putExtra(TTSAnnouncementService.EXTRA_PRAYER_NAME, prayerName)
                            putExtra(TTSAnnouncementService.EXTRA_MOSQUE_NAME, mosqueName)
                            putExtra("slot_index", 0)
                        }

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(ttsIntent)
                            } else {
                                context.startService(ttsIntent)
                            }
                            Log.d(TAG, "✅ Started TTSAnnouncementService for $prayerName")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to start TTSAnnouncementService", e)
                        }

                        abandonDuckAudioFocusDelayed(audioManager, focusRequest, 2500L)
                        return // 🛑 CRITICAL: Exit here
                    }

                    "azan", null -> {
                        // FULL AZAN MODE (default)
                        Log.d("DEBUG_AUDIO", "Executing Azan Branch for $prayerName")

                        // Map prayer name to audio resource
                        val soundResId = when (prayerName) {
                            "Fajr" -> R.raw.morning1
                            "Dhuhr", "Asr", "Maghrib", "Isha" -> R.raw.azan
                            "Jumu'ah", "Jumu'ah1" -> R.raw.azan
                            "Jumu'ah2" -> R.raw.jumua2
                            else -> 0
                        }

                        if (soundResId != 0) {
                            // Only call startForegroundService here!
                            val azanIntent = Intent(context, AzanPlaybackService::class.java).apply {
                                putExtra(AzanPlaybackService.EXTRA_PRAYER_NAME, prayerName)
                                putExtra(AzanPlaybackService.EXTRA_SOUND_RES_ID, soundResId)
                                putExtra(AzanPlaybackService.EXTRA_PRAYER_TIME, prayerTime)
                                putExtra(AzanPlaybackService.EXTRA_MOSQUE_NAME, mosqueName)
                                putExtra(AzanPlaybackService.EXTRA_MOSQUE_ID, intent.getStringExtra(EXTRA_MOSQUE_ID) ?: "")
                                putExtra("slot_index", 0)
                            }

                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(azanIntent)
                                } else {
                                    context.startService(azanIntent)
                                }
                                Log.d(TAG, "✅ Started AzanPlaybackService for $prayerName")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to start AzanPlaybackService", e)
                            }
                        } else {
                            Log.w(TAG, "⚠️ No audio resource mapped for '$prayerName'")
                        }

                        abandonDuckAudioFocusDelayed(audioManager, focusRequest, 1500L)
                        return // 🛑 Exit after Azan
                    }

                    else -> {
                        // Unknown/Invalid value
                        Log.e("DEBUG_AUDIO", "⚠️ UNKNOWN audio style: [$mainSlotAudioStyle] - No audio will play")
                        return // 🛑 Exit - no playback
                    }
                }
                // 🚨 UNREACHABLE: All branches return above
            } else {
                // Slots 1/2/3 (Secondary): PRIMARY = TTS voice, SECONDARY = silent.

                // ─────────────────────────────────────────────────────────────────────────────
                // REGRESSION 1 FIX: Honor mute & global-disable states at trigger time.
                // AlarmSurgeryManager gates these at scheduling time, but the user may change
                // settings AFTER alarms were installed — so we re-check here on every fire.
                // NOTE: These checks intentionally apply even in debug mode (parity with prod).
                // ─────────────────────────────────────────────────────────────────────────────

                // Gate 1: secondary notifications globally disabled
                val secondaryMasterEnabled = prefs.getBoolean("pref_secondary_notifications_enabled", true)
                if (!secondaryMasterEnabled) {
                    Log.w(TAG, "🚫 ABORT: pref_secondary_notifications_enabled=false; suppressing secondary alarm for $prayerName mosque=$mosqueId")
                    abandonDuckAudioFocusDelayed(audioManager, focusRequest, 0L)
                    return
                }

                // Gate 2: per-mosque per-prayer bell-icon mute (DataStore-backed)
                val isPerPrayerMuted = try {
                    runBlocking {
                        context.mosquePreferencesDataStore.data.first().let { dsPrefs ->
                            val key = androidx.datastore.preferences.core.booleanPreferencesKey("mute_${mosqueId}_${prayerName}")
                            dsPrefs[key] ?: false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read per-prayer mute state for $mosqueId/$prayerName; defaulting to NOT muted", e)
                    false
                }
                if (isPerPrayerMuted) {
                    Log.w(TAG, "🔕 ABORT: Per-prayer mute active for $prayerName at mosque=$mosqueId; suppressing notification")
                    abandonDuckAudioFocusDelayed(audioManager, focusRequest, 0L)
                    return
                }

                Log.d(TAG, "✅ Mute/disable gates passed for secondary prayer=$prayerName mosque=$mosqueId")

                // ✅ Ghost-Alarm Guard #1: validate the UTC target millis (frozen extras) against now.
                // Tolerance is 90s — enough to cover AlarmManager delivery jitter on Samsung,
                // but tight enough to reject an alarm that fired ~2 minutes early (zombie scenario).
                val targetMillis = intent.getLongExtra(EXTRA_TARGET_MILLIS, 0L)
                if (targetMillis > 0L) {
                    val diffMs = kotlin.math.abs(System.currentTimeMillis() - targetMillis)
                    if (isDebugMosque) {
                        Log.w(TAG, "⚠️ DEBUG: Skipping Ghost Alarm Guard for test mosque=$mosqueId (diffMs=$diffMs)")
                    } else if (diffMs > 90_000L) {
                        Log.w(TAG, "🚫 Ghost alarm blocked: targetMillis=$targetMillis now=${System.currentTimeMillis()} diffMs=${diffMs}ms (>90s)")
                        return
                    }
                }

                // ✅ Golden Rule (Slots 1+): PRIMARY = TTS, SECONDARY = silent.
                val isFriday = nowInMosqueZone.dayOfWeek == java.time.DayOfWeek.FRIDAY
                val fridayMode = prefs.getString("pref_friday_mode", "merge") ?: "merge"

                val isSecondaryEvent = when {
                    prayerName == "Duha" -> true
                    isFriday && fridayMode == "split_jumuah" && prayerName == "Dhuhr" -> true
                    isFriday && fridayMode == "split_dhuhr" && (prayerName == "Jumu'ah" || prayerName == "Jumu'ah1") -> true
                    prayerName == "Jumu'ah2" || prayerName == "Jumu'ah 2" -> true
                    else -> false
                }

                if (isSecondaryEvent) {
                    Log.d(TAG, "Secondary event on Slots 1+: silent by rule. prayer=$prayerName mode=$fridayMode")
                    abandonDuckAudioFocusDelayed(audioManager, focusRequest, 500L)
                    return
                }

                val isTTSEnabled = try {
                    TTSAnnouncementHelper.isTTSEnabled(context, mosqueId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking TTS mode, defaulting to tone", e)
                    false
                }

                // 🚫 STRICT GATE: Don't schedule TTS if Slot 0 is set to tone mode
                val slot1AudioStyle = prefs.getString("pref_main_slot_audio_style", "azan")
                val secondaryAudioMode = prefs.getString("pref_secondary_audio_mode", "tts") ?: "tts"

                // Determine tone preference for this secondary event
                val secondaryTonePref: String? = when {
                    // tone-only or tone+TTS modes: always play tone
                    secondaryAudioMode == "tone" || secondaryAudioMode == "tone_tts" ->
                        prefs.getString("pref_secondary_tone", "tone1") ?: "tone1"
                    // Pure TTS mode: no tone prefix
                    else -> null
                }

                val shouldPlayTTS = isTTSEnabled && slot1AudioStyle != "tone"

                val actualSlotIndex = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT_INDEX, 1)

                // ── Build TTS intent (constructed here, launched by the queue) ──────────
                val ttsIntent: Intent? = if (shouldPlayTTS) {
                    Intent(context, TTSAnnouncementService::class.java).apply {
                        putExtra(TTSAnnouncementService.EXTRA_PRAYER_NAME, prayerName)
                        putExtra(TTSAnnouncementService.EXTRA_MOSQUE_NAME, mosqueName)
                        putExtra("slot_index", actualSlotIndex)
                    }
                } else null

                // ── Strategy 1 (Azan conflict): Android's AudioFocus DUCKING handles  ──
                // volume reduction natively when our USAGE_NOTIFICATION_EVENT source     ──
                // requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK. The OS lowers the Azan   ──
                // (USAGE_ALARM stream) automatically and restores it after. No extra     ──
                // code needed — just enqueue normally and the OS mediates the volume.    ──
                //                                                                        ──
                // ── Strategy 2 (Non-Azan conflict): NotificationQueue serialises two   ──
                // secondary events that fire at the same instant, inserting a 10-second ──
                // gap between them so the user hears them as distinct alerts.            ──

                Log.i(TAG, "⏳ Enqueuing secondary notification: $mosqueName/$prayerName " +
                        "tone=$secondaryTonePref tts=$shouldPlayTTS azanPlaying=${PlaybackState.isAzanPlaying.value}")

                NotificationQueue.enqueue(
                    NotificationQueue.QueueItem.SecondaryNotification(
                        context       = context.applicationContext,
                        tonePrefValue = secondaryTonePref,
                        ttsIntent     = ttsIntent,
                        isTTSEnabled  = shouldPlayTTS,
                        label         = "$mosqueName/$prayerName",
                        prayerName    = prayerName,
                        mosqueName    = mosqueName,
                        slotIndex     = actualSlotIndex
                    )
                )

                // Audio focus is released after the estimated total playback duration.
                val estimatedMs = (if (secondaryTonePref != null) SHORT_TONE_MAX_MS else 0L) +
                        (if (shouldPlayTTS) 2500L else 0L)
                abandonDuckAudioFocusDelayed(audioManager, focusRequest, estimatedMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling prayer alarm", e)
        } finally {
            wakeLock?.release()
        }
    }

    private fun playShortNotification(context: Context, prayerName: String): Boolean {
        // ─────────────────────────────────────────────────────────────────────
        // FIX: Use the user's selected secondary tone from res/raw via TonePlayer
        // instead of system ringtone URIs which ignore user preference and may
        // produce different volume levels than the alarm stream.
        // ─────────────────────────────────────────────────────────────────────
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val selectedTone = prefs.getString("pref_secondary_tone", "tone1") ?: "tone1"

        Log.d(TAG, "Secondary short tone: using pref_secondary_tone=[$selectedTone] for prayer=$prayerName")

        val success = TonePlayer.playSelectedTone(context, selectedTone)

        if (!success) {
            // Fallback: system default notification sound
            Log.w(TAG, "TonePlayer failed for tone=$selectedTone; falling back to system default")
            return try {
                val fallbackUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                val ringtone = android.media.RingtoneManager.getRingtone(context, fallbackUri)
                ringtone?.apply {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    try { if (ringtone?.isPlaying == true) ringtone.stop() } catch (_: Throwable) {}
                }, SHORT_TONE_MAX_MS)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Fallback ringtone also failed", e)
                false
            }
        }
        return true
    }

    private fun requestDuckAudioFocus(audioManager: AudioManager): AudioFocusRequest? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                val res = audioManager.requestAudioFocus(req)
                Log.d(TAG, "AudioFocus request (duck) result=$res")
                req
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request failed", e)
            null
        }
    }

    private fun abandonDuckAudioFocusDelayed(
        audioManager: AudioManager,
        request: AudioFocusRequest?,
        delayMs: Long
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    request?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            } catch (_: Throwable) {
            }
        }, delayMs)
    }

    // ── Idempotent dedup lock ────────────────────────────────────────────────

    /**
     * Returns true  → this is a duplicate / zombie alarm. Caller must abort.
     * Returns false → this is a new event. The lock has been written; caller may proceed.
     *
     * Signature key:  fired_<mosqueId>_<prayerName>_<date-in-mosque-tz>
     *
     * Using the mosque's own timezone for the date portion prevents false-positives at
     * device-local midnight where the device date flips but the mosque date has not yet.
     *
     * The stored value is the epoch-ms of the first trigger, which enables the 55-second
     * window check AND the 48-hour TTL pruning from a single Long per entry.
     */
    private fun isDuplicateEvent(
        context: Context,
        mosqueId: String,
        prayerName: String,
        mosqueTimeZone: String
    ): Boolean {
        val dedupPrefs = context.getSharedPreferences(DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()

        // Build date string in the mosque's own timezone to avoid cross-midnight edge cases
        val zoneId = runCatching { ZoneId.of(mosqueTimeZone) }.getOrElse { ZoneId.of("UTC") }
        val dateStr = ZonedDateTime.now(zoneId)
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) // "2026-03-03"
        val eventKey = "fired_${mosqueId}_${prayerName}_${dateStr}"

        // ── Check ────────────────────────────────────────────────────────────
        val firstFiredMs = dedupPrefs.getLong(eventKey, -1L)
        if (firstFiredMs != -1L) {
            // Use abs() to handle MIUI/Samsung clock jitter where System.currentTimeMillis()
            // can return a value slightly BEFORE the stored timestamp (negative age).
            // e.g. "fired -37ms after first trigger" — without abs() this evaluates to
            // ageMs = -37 which fails the <= DEDUP_WINDOW_MS check and lets the duplicate through.
            val ageMs = kotlin.math.abs(nowMs - firstFiredMs)
            if (ageMs <= DEDUP_WINDOW_MS) {
                Log.w(TAG, "🔒 DEDUP: Duplicate/Zombie alarm detected for $eventKey " +
                        "(age=${nowMs - firstFiredMs}ms |abs|=${ageMs}ms, window=${DEDUP_WINDOW_MS}ms). Aborting.")
                return true
            }
        }

        // ── Lock ─────────────────────────────────────────────────────────────
        // Write BEFORE any async work so a racing duplicate sees the lock immediately.
        dedupPrefs.edit().putLong(eventKey, nowMs).apply()
        Log.d(TAG, "🔒 DEDUP: Lock acquired for $eventKey")
        return false
    }

    /**
     * Prunes SharedPreferences entries older than DEDUP_TTL_MS (48 h).
     * Called from the midnight-refresh branch so housekeeping runs once per day
     * without needing a separate WorkManager job.
     */
    private fun pruneStaleDedupEntries(context: Context) {
        val dedupPrefs = context.getSharedPreferences(DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()
        val editor = dedupPrefs.edit()
        var pruned = 0
        dedupPrefs.all.forEach { (key, value) ->
            val storedMs = value as? Long ?: return@forEach
            if (nowMs - storedMs > DEDUP_TTL_MS) {
                editor.remove(key)
                pruned++
            }
        }
        if (pruned > 0) {
            editor.apply()
            Log.d(TAG, "🔒 DEDUP: Pruned $pruned stale entries (older than 48 h)")
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrayerZones:PrayerAlarmWakeLock").apply {
                    acquire(WAKE_LOCK_TIMEOUT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
            null
        }
    }
}

