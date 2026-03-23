package com.abang.prayerzones.util

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.text.SpannableString
import android.text.style.TtsSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Persistent TTS engine that owns TextToSpeech lifecycle.
 * Reuses a single TTS instance across multiple announcements to prevent "sometimes silent" bug.
 */
class TTSAnnouncementEngine(private val appContext: Context) {

    companion object {
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val TAG = "TTSAnnouncementEngine"
        private const val WARM_ENGINE_TIMEOUT_MS = 10 * 60 * 1000L // ✅ FIX #3: 10 minutes
    }

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Persistent TTS instance
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isInitializing = false

    private val scope = CoroutineScope(Dispatchers.Main)

    // ✅ FIX #3: Shutdown timer job to keep engine warm for 10 minutes
    private var shutdownTimerJob: kotlinx.coroutines.Job? = null

    // Queue for pending announcements during initialization
    private data class PendingAnnouncement(
        val prayerName: String,
        val mosqueName: String,
        val usage: Int,
        val onCompleted: () -> Unit
    )
    private var pendingAnnouncement: PendingAnnouncement? = null

    /**
     * Normalizes mosque names for better pronunciation.
     * For Arabic TTS, we replace French/English "Mosque" terms with "مسجد" and join "Al " -> "ال".
     */
    private fun translateMosqueNameForTts(mosqueName: String, lang: String): String {
        if (lang.lowercase() != "ar") return mosqueName

        return mosqueName
            .replace("Mosquée", "مسجد", ignoreCase = true)
            .replace("Mosque", "مسجد", ignoreCase = true)
            .replace("Masjid", "مسجد", ignoreCase = true)
            .replace("Al ", "ال", ignoreCase = true)
    }

    /**
     * Returns true if Google TTS is installed/enabled on the device.
     */
    private fun isGoogleTtsAvailable(): Boolean {
        return try {
            val pm = appContext.packageManager
            pm.getApplicationInfo(GOOGLE_TTS_PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Open the system TTS settings screen so users can install voice data.
     */
    fun buildTtsSettingsIntent(): Intent {
        return Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveLocaleWithFallback(ttsLocal: TextToSpeech, requested: Locale): Locale {
        // Requirement: verify COUNTRY available first.
        val countryRes = try {
            ttsLocal.isLanguageAvailable(requested)
        } catch (_: Throwable) {
            TextToSpeech.LANG_AVAILABLE
        }

        if (countryRes >= TextToSpeech.LANG_COUNTRY_AVAILABLE) return requested

        // Fallback chain
        val fallbacks: List<Locale> = when (requested.language.lowercase()) {
            "ar" -> listOf(Locale("ar"), Locale.US)
            "fr" -> listOf(Locale.FRENCH, Locale.US)
            else -> listOf(Locale.US)
        }

        for (candidate in fallbacks) {
            val res = try {
                ttsLocal.isLanguageAvailable(candidate)
            } catch (_: Throwable) {
                TextToSpeech.LANG_AVAILABLE
            }
            if (res >= TextToSpeech.LANG_COUNTRY_AVAILABLE || res == TextToSpeech.LANG_AVAILABLE) {
                Log.w("ALARM_SURGERY", "Locale fallback: requested=${requested.toLanguageTag()} -> using=${candidate.toLanguageTag()} (res=$res)")
                return candidate
            }
        }

        return Locale.US
    }

    fun speakAnnouncement(
        prayerName: String,
        mosqueName: String,
        usage: Int,
        onCompleted: () -> Unit
    ) {
        Log.d(TAG, "speakAnnouncement called for $prayerName at $mosqueName")

        // ✅ FIX #3: Cancel shutdown timer - we're using the engine now!
        shutdownTimerJob?.cancel()
        Log.d(TAG, "✅ Cancelled shutdown timer - reusing warm engine")

        // Check if already speaking - prevent overlapping announcements
        if (tts?.isSpeaking == true) {
            Log.w(TAG, "TTS is already speaking, skipping announcement for $prayerName")
            onCompleted()
            return
        }

        // If TTS is ready, speak immediately
        if (isTtsReady && tts != null) {
            Log.d(TAG, "TTS is ready, speaking immediately (engine already warm)")
            performAnnouncement(prayerName, mosqueName, usage, onCompleted)
            return
        }

        // If already initializing, queue this announcement
        if (isInitializing) {
            Log.d(TAG, "TTS is initializing, queuing announcement")
            pendingAnnouncement = PendingAnnouncement(prayerName, mosqueName, usage, onCompleted)
            return
        }

        // Initialize TTS and queue the announcement
        Log.d(TAG, "TTS not ready, initializing...")
        pendingAnnouncement = PendingAnnouncement(prayerName, mosqueName, usage, onCompleted)
        initializeTTS()
    }

    /**
     * Initialize the persistent TTS engine (only called when needed).
     */
    private fun initializeTTS() {
        if (isInitializing || isTtsReady) {
            Log.d(TAG, "TTS already initializing or ready, skipping init")
            return
        }

        isInitializing = true

        // Prefer Google TTS when available
        val enginePackage: String? = if (isGoogleTtsAvailable()) GOOGLE_TTS_PACKAGE else null
        Log.d(TAG, "Initializing TTS engine: ${enginePackage ?: "(system default)"}")

        tts = if (enginePackage != null) {
            TextToSpeech(appContext.applicationContext, { status ->
                handleTTSInit(status)
            }, enginePackage)
        } else {
            TextToSpeech(appContext.applicationContext) { status ->
                handleTTSInit(status)
            }
        }
    }

    /**
     * Handle TTS initialization result and process pending announcement.
     */
    private fun handleTTSInit(status: Int) {
        Log.i("Probe", "6. TTS Init Status: $status (SUCCESS=${TextToSpeech.SUCCESS})")
        isInitializing = false

        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS initialization failed: $status")
            isTtsReady = false
            pendingAnnouncement?.onCompleted?.invoke()
            pendingAnnouncement = null
            return
        }

        isTtsReady = true
        Log.d(TAG, "✅ TTS initialized successfully")

        // Process pending announcement if any
        pendingAnnouncement?.let { pending ->
            Log.d(TAG, "Processing queued announcement for ${pending.prayerName}")
            performAnnouncement(
                pending.prayerName,
                pending.mosqueName,
                pending.usage,
                pending.onCompleted
            )
            pendingAnnouncement = null
        }
    }

    /**
     * Perform the actual TTS announcement (called only when TTS is ready).
     */
    private fun performAnnouncement(
        prayerName: String,
        mosqueName: String,
        usage: Int,
        onCompleted: () -> Unit
    ) {
        val ttsLocal = tts ?: run {
            Log.e(TAG, "TTS instance is null in performAnnouncement")
            onCompleted()
            return
        }

        Log.d(TAG, "Performing announcement for $prayerName at $mosqueName")


        // Re-fetch settings to ensure we use the latest values
        val currentLanguageCode = TTSAnnouncementHelper.getTTSLanguage(appContext)
        val currentSpeed = TTSAnnouncementHelper.getTTSSpeed(appContext)

        // Beep-before-TTS toggle (SharedPreferences)
        val playAttentionTone = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext)
            .getBoolean("pref_attention_tone_before_tts", true)

        // ✅ Use SharedPreferences for TTS voice selection (not DataStore)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        val preferredVoiceName: String? = prefs.getString("pref_tts_voice_name", null)

        // Step A: Extract language key from selected voice name (e.g., "fr-FR-x-..." → "fr")
        val languageKeyFromVoice: String? = preferredVoiceName
            ?.trim()
            ?.take(2)
            ?.lowercase()

        val effectiveLanguageKey: String = TranslationRegistry.normalizeLanguageKey(
            languageKeyFromVoice ?: currentLanguageCode
        )

        // Step B: setLanguage(Locale(langCode)) based on the selected voice language key.
        val requestedLocale = when (effectiveLanguageKey) {
            "en" -> Locale.US
            "fr" -> Locale.FRANCE
            "ar" -> Locale("ar")
            "bn" -> Locale("bn")
            "hi" -> Locale("hi")
            "id" -> Locale("id")
            "tr" -> Locale("tr")
            "ur" -> Locale("ur")
            else -> Locale.US
        }

        val safeLocale = resolveLocaleWithFallback(ttsLocal, requestedLocale)

        // Step C: Dynamic translation using TranslationRegistry
        // Pass raw prayer name - the registry handles localization
        val textToSpeak: String = TranslationRegistry.getAnnouncement(
            prayerName = prayerName.trim(),
            mosqueName = mosqueName,
            languageKey = effectiveLanguageKey
        )

        Log.d(
            TAG,
            "TTS config: lang=$effectiveLanguageKey voice=${preferredVoiceName ?: "(auto)"} locale=${safeLocale.displayName} speed=$currentSpeed text='$textToSpeak'"
        )

        // Audio routing - Use ALARM stream with SONIFICATION content type
        // This ensures TTS plays at the same volume as notification tones/alarms
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            ttsLocal.setAudioAttributes(attributes)
            Log.d(TAG, "TTS audio attributes set: USAGE_ALARM + CONTENT_TYPE_SONIFICATION")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set TTS audio attributes", e)
        }

        requestAudioFocus()

        scope.launch {
            // Increased delay for consistency (1000ms)
            delay(1000)

            val langRes = ttsLocal.setLanguage(safeLocale)
            Log.d(TAG, "setLanguage result=$langRes")

            val selectionResult = selectBestVoiceForLocale(
                tts = ttsLocal,
                locale = safeLocale,
                preferredVoiceName = preferredVoiceName
            )

            val chosenVoice = selectionResult.voice

            if (chosenVoice != null) {
                val voiceRes = ttsLocal.setVoice(chosenVoice)
                Log.d(TAG, "Voice set to: ${chosenVoice.name} (res=$voiceRes)")

                // Only show Toast if the preferred voice was requested BUT not found
                if (preferredVoiceName != null && !selectionResult.isPreferredMatch) {
                    Log.w(TAG, "Preferred voice '$preferredVoiceName' not available; using '${chosenVoice.name}'")
                    ToastUtils.show(appContext, "Your preferred TTS voice isn't available. Using default instead.")
                }
            } else {
                Log.w(TAG, "No voice match for locale ${safeLocale.toLanguageTag()}; using engine default=${ttsLocal.voice?.name}")
                if (preferredVoiceName != null) {
                    ToastUtils.show(appContext, "Your preferred TTS voice isn't available. Using default instead.")
                }
            }

            ttsLocal.setSpeechRate(currentSpeed)

            if (playAttentionTone) {
                runCatching {
                    val toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    if (toneUri != null) {
                        // ✅ FIX: Use MediaPlayer directly instead of RingtoneManager.getRingtone().
                        // On Samsung Android 11, getRingtone() calls getActualDefaultRingtoneUri()
                        // which tries to write-back a resolved URI to Settings.System — triggering
                        // a SecurityException (WRITE_SETTINGS not granted). MediaPlayer.create()
                        // opens the URI via ContentResolver and avoids that code path entirely.
                        val mp = android.media.MediaPlayer().apply {
                            val attrs = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            setAudioAttributes(attrs)
                            try {
                                setDataSource(appContext, toneUri)
                                prepare()
                            } catch (e: Exception) {
                                Log.w(TAG, "Attention tone setDataSource failed, skipping", e)
                                release()
                                return@runCatching
                            }
                        }
                        Log.d(TAG, "Attention tone: USAGE_ALARM + CONTENT_TYPE_SONIFICATION (MediaPlayer)")
                        mp.start()
                        delay(500)
                        mp.stop()
                        mp.release()
                    }
                }.onFailure {
                    Log.w(TAG, "Attention tone failed; continuing with TTS", it)
                }
            }

            val utteranceText: String = try {
                val spannable = SpannableString(textToSpeak)
                val span = TtsSpan.TextBuilder(textToSpeak).build()
                spannable.setSpan(span, 0, textToSpeak.length, 0)
                spannable.toString()
            } catch (_: Throwable) {
                textToSpeak
            }

            val utteranceId = "prayer_alarm_tts_${System.currentTimeMillis()}"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            // Set up completion listener
            ttsLocal.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS completed: $utteranceId")
                    onCompleted()
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error: $utteranceId")
                    onCompleted()
                }
            })

                        // Android 11 can "forget" language between utterances
            ttsLocal.setLanguage(safeLocale)

            // CRITICAL: Re-apply the chosen voice AFTER setLanguage().
            // setLanguage() resets the voice to the engine default, undoing the
            // selectBestVoiceForLocale() result above.  This is the root cause of
            // "Test button works, real announcements use wrong voice".
            chosenVoice?.let {
                ttsLocal.setVoice(it)
                Log.d(TAG, "Re-applied voice after setLanguage: ${it.name}")
            }

            Log.i("Probe", "7. Speak called. Text length: ${utteranceText.length}, text='$utteranceText'")
            val speakRes = ttsLocal.speak(utteranceText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Log.d(TAG, "speak() result=$speakRes for text: '$utteranceText'")
        }
    }

    /**
     * Selects the best available voice for the given locale.
     * Returns a result indicating if the preferred voice was matched.
     */
    data class VoiceSelectionResult(val voice: Voice?, val isPreferredMatch: Boolean)

    private fun selectBestVoiceForLocale(
        tts: TextToSpeech,
        locale: Locale,
        preferredVoiceName: String? = null
    ): VoiceSelectionResult {
        val voices = tts.voices ?: return VoiceSelectionResult(null, false)

        // Broaden to language-only match for better compatibility
        val candidates = voices.filter { it.locale.language == locale.language }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No voices found for language: ${locale.language}")
            return VoiceSelectionResult(null, false)
        }

        // Try to match preferred voice by name
        preferredVoiceName?.let { pref ->
            candidates.firstOrNull { it.name == pref }?.let { matched ->
                Log.d(TAG, "Matched preferred voice: ${matched.name}")
                return VoiceSelectionResult(matched, true)
            }
        }

        // Fallback to highest quality voice
        val best = candidates.maxByOrNull { it.quality }
        if (best != null) {
            Log.d(TAG, "Auto-selected voice: ${best.name} (quality=${best.quality})")
        }
        return VoiceSelectionResult(best, false)
    }

    private fun requestAudioFocus() {
        try {
            val focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attr)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
                Log.d(TAG, "Audio focus requested: USAGE_ALARM")
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, focusGain)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
        }
    }

    /**
     * ✅ FIX #3: Schedule delayed shutdown (10 minutes) instead of immediate shutdown.
     * This keeps the TTS engine warm for subsequent prayers, preventing the 2-minute lag bug.
     * Called when the service is destroyed or app is closed.
     */
    fun shutdown() {
        // Cancel any existing shutdown timer
        shutdownTimerJob?.cancel()

        // Schedule shutdown after 10 minutes of inactivity
        shutdownTimerJob = scope.launch {
            Log.d(TAG, "⏱️ Scheduling TTS shutdown in 10 minutes (warm engine timeout)")
            delay(WARM_ENGINE_TIMEOUT_MS)
            performActualShutdown()
        }
    }

    /**
     * Immediately shutdown the TTS engine (used when app is force-closed).
     */
    fun shutdownImmediate() {
        shutdownTimerJob?.cancel()
        performActualShutdown()
    }

    /**
     * Internal method that performs the actual TTS engine shutdown.
     */
    private fun performActualShutdown() {
        Log.d(TAG, "🔴 Performing actual TTS engine shutdown")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            }
        } catch (_: Throwable) {
        }

        try {
            tts?.stop()
        } catch (_: Throwable) {
        }

        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }

        // Reset state
        tts = null
        isTtsReady = false
        isInitializing = false
        pendingAnnouncement = null
        shutdownTimerJob = null

        Log.d(TAG, "TTS engine shutdown complete")
    }

    private fun launchInstallTtsData() {
        try {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
        } catch (e: Exception) {
            Log.w("ALARM_SURGERY", "Failed to launch TTS data install", e)
        }
    }
}
