package com.abang.prayerzones.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.abang.prayerzones.R
import com.abang.prayerzones.util.TTSAnnouncementEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.preference.PreferenceManager

import com.abang.prayerzones.util.TonePlayer

/**
 * Notification settings fragment - Detailed audio configuration
 * Loads notification_preferences.xml and handles:
 * - Slot 1 full Azan controls
 * - Secondary audio mode (Silent/Tone/TTS)
 * - TTS settings (language, voice, speed)
 * - TTS preview functionality
 */
@AndroidEntryPoint
class NotificationSettingsFragment : PreferenceFragmentCompat() {

    // TextToSpeech engine for preview
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var isTtsReady = false

    companion object {
        private const val TAG = "NotificationSettings"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Allow the list to draw behind the system navigation bar, then add
        // padding equal to the bar height so the last item is never hidden.
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = bottomInset)
            insets
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // IMPORTANT: Do NOT set a custom PreferenceDataStore here.
        // For the UI-linked global toggles we rely on DefaultSharedPreferences
        // so the main screen + scheduler can read them consistently.

        // Load the preferences from XML
        setPreferencesFromResource(R.xml.notification_preferences, rootKey)

        // Initialize TTS engine
        initializeTTS()

        // Set up preference listeners
        setupMainSlotAudioStyleListener()
        setupTTSVisibilityListeners()
        setupTTSSettings()
        setupInstallVoiceDataButton()
        setupTestTTSButton()

        // NEW: Wire tone picker + test button
        setupTonePreferences()

    }

    /**
     * Listen to main slot audio style changes to show/hide tone picker.
     * NOTE: this must NOT set onPreferenceChangeListener itself — the unified
     * listener in setupTTSVisibilityListeners() handles ALL changes to this
     * preference so we never accidentally overwrite each other.
     * We only run the initial visibility pass here.
     */
    private fun setupMainSlotAudioStyleListener() {
        findPreference<ListPreference>("pref_main_slot_audio_style")?.apply {
            updateSlot1ToneVisibility(value)
        }
        // Also apply master toggle visibility on screen open
        val masterEnabled = findPreference<androidx.preference.SwitchPreferenceCompat>(
            "pref_notifications_enabled"
        )?.isChecked ?: true
        updateSlot1AudioPrefsVisibility(masterEnabled)
    }

    /**
     * When Slot 1 master toggle is OFF, hide Audio Style, Main Slot Tone, and Azan Volume.
     * When ON, restore visibility (tone picker still subject to audio-style check).
     */
    private fun updateSlot1AudioPrefsVisibility(enabled: Boolean) {
        findPreference<ListPreference>("pref_main_slot_audio_style")?.isVisible = enabled
        if (!enabled) {
            // Master toggle OFF — hide everything
            findPreference<ListPreference>("pref_main_slot_tone")?.isVisible = false
            findPreference<SeekBarPreference>("pref_slot1_volume")?.isVisible = false
        } else {
            // Master toggle ON — re-apply per-style rules
            val style = findPreference<ListPreference>("pref_main_slot_audio_style")?.value
            updateSlot1ToneVisibility(style)
            updateSlot1VolumeVisibility(style)
        }
        Log.d(TAG, "Slot 1 audio prefs visibility: $enabled")
    }

    /**
     * Show Azan Volume only when "Full Azan (MP3)" is selected.
     * Hidden for "Short Tone" and "TTS Voice".
     */
    private fun updateSlot1VolumeVisibility(audioStyle: String?) {
        val shouldShow = audioStyle == "azan"
        findPreference<SeekBarPreference>("pref_slot1_volume")?.isVisible = shouldShow
        Log.d(TAG, "Slot 1 Volume visibility: $shouldShow (audioStyle=$audioStyle)")
    }

    /**
     * When secondary master toggle is OFF, hide Audio Style as well.
     * (pref_secondary_tone and TTS category are handled by their own listeners.)
     */
    private fun updateSecondaryAudioModeVisibility(enabled: Boolean) {
        findPreference<ListPreference>("pref_secondary_audio_mode")?.isVisible = enabled
        // Also hide secondary tone and TTS category when toggle is OFF
        if (!enabled) {
            findPreference<ListPreference>("pref_secondary_tone")?.isVisible = false
            findPreference<PreferenceCategory>("pref_tts_settings_category")?.isVisible = false
        } else {
            // Re-apply tone and TTS visibility based on current audio mode
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val currentMode = prefs.getString("pref_secondary_audio_mode", "tone")
            updateSecondaryToneVisibility(currentMode)
            updateTTSCategoryVisibility()
        }
        Log.d(TAG, "Secondary audio mode visibility: $enabled")
    }

    /**
     * Show/hide Slot 1 tone picker based on audio style.
     * Tone picker should only be visible when audio style is "tone".
     * Summary is handled automatically by useSimpleSummaryProvider="true" in XML.
     */
    private fun updateSlot1ToneVisibility(audioStyle: String?) {
        val tonePicker = findPreference<ListPreference>("pref_main_slot_tone")
        val shouldShowTone = audioStyle == "tone"
        tonePicker?.isVisible = shouldShowTone
        Log.d(TAG, "Slot 1 Tone Picker visibility: $shouldShowTone (audioStyle=$audioStyle)")
    }

    /**
     * Show/hide the Secondary Slot Tone picker based on the secondary audio mode.
     * Rule: hide ONLY when "tts" is selected — that is the only mode that uses no tone.
     * "tone" and "tone_tts" both need the tone picker visible.
     */
    private fun updateSecondaryToneVisibility(audioMode: String?) {
        val tonePicker = findPreference<ListPreference>("pref_secondary_tone") ?: return
        // Show for "tone" and "tone_tts"; hide only for pure "tts"
        val shouldShowTone = audioMode != "tts"
        tonePicker.isVisible = shouldShowTone
        Log.d(TAG, "Secondary Tone Picker visibility: $shouldShowTone (audioMode=$audioMode)")
    }

    private fun setupTonePreferences() {

        // Slot 1 tone picker
        val slot1TonePref: ListPreference? = findPreference("pref_main_slot_tone")
        slot1TonePref?.setOnPreferenceChangeListener { _, newValue ->
            val tonePrefValue = newValue as String
            TonePlayer.playSelectedTone(requireContext(), tonePrefValue)
            Log.d("NotificationSettings", "Slot 1 tone changed to $tonePrefValue (auto-play)")
            true // allow save
        }

        // Secondary tone picker
        val secondaryTonePref: ListPreference? = findPreference("pref_secondary_tone")
        secondaryTonePref?.setOnPreferenceChangeListener { _, newValue ->
            val tonePrefValue = newValue as String
            TonePlayer.playSelectedTone(requireContext(), tonePrefValue)
            Log.d("NotificationSettings", "Secondary tone changed to $tonePrefValue (auto-play)")
            true // allow save
        }
    }

    /**
     * Initialize TextToSpeech engine for preview functionality
     */
    private fun initializeTTS() {
        try {
            // Specify Google's engine package name
            val googleTtsPackage = "com.google.android.tts"

            // Use the 3-parameter constructor to force Google TTS
            tts = TextToSpeech(requireContext(), { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    isTtsReady = true
                    Log.d(TAG, "✅ Google TTS initialized successfully")

                    try {
                        val language = getCurrentTTSLanguage()
                        val result = tts?.setLanguage(language)

                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "⚠️ Language $language not supported by Google Engine")
                            tts?.setLanguage(Locale.US)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting TTS language", e)
                    }
                    // Trigger UI refresh after TTS is ready
                    populateVoiceListForLocale(getCurrentTTSLanguage())
                } else {
                    ttsInitialized = false
                    isTtsReady = false
                    Log.e(TAG, "❌ TTS initialization failed: $status")
                }
            }, googleTtsPackage)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error forcing Google TTS", e)
            // Fallback to default if Google TTS is completely missing from the phone
            tts = TextToSpeech(requireContext()) { /* status -> ... */ }
        }
    }

    /**
     * Unified listener setup for TTS category visibility AND Slot 1 tone picker visibility.
     *
     * IMPORTANT: This is the single source of truth for pref_main_slot_audio_style changes.
     * setupMainSlotAudioStyleListener() only runs the initial pass — it does NOT set its own
     * onPreferenceChangeListener to avoid overwriting this one.
     */
    private fun setupTTSVisibilityListeners() {
        // ── Slot 1 master toggle ──────────────────────────────────────────────
        findPreference<androidx.preference.SwitchPreferenceCompat>("pref_notifications_enabled")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    updateSlot1AudioPrefsVisibility(newValue as Boolean)
                    updateTTSCategoryVisibility()
                }
                true
            }

        // ── Slot 1 audio style ────────────────────────────────────────────────
        findPreference<ListPreference>("pref_main_slot_audio_style")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val style = newValue as? String
                    updateSlot1ToneVisibility(style)
                    updateSlot1VolumeVisibility(style)
                    updateTTSCategoryVisibility()
                }
                true
            }

        // ── Secondary master toggle ───────────────────────────────────────────
        findPreference<androidx.preference.SwitchPreferenceCompat>("pref_secondary_notifications_enabled")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    updateSecondaryAudioModeVisibility(newValue as Boolean)
                    updateTTSCategoryVisibility()
                }
                true
            }

        // ── Secondary audio mode ──────────────────────────────────────────────
        findPreference<ListPreference>("pref_secondary_audio_mode")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val currentMode = prefs.getString("pref_secondary_audio_mode", "tone")
                    updateSecondaryToneVisibility(currentMode)
                    updateTTSCategoryVisibility()
                }
                true
            }

        // ── Initial pass on screen open ───────────────────────────────────────
        val slot1Enabled = findPreference<androidx.preference.SwitchPreferenceCompat>(
            "pref_notifications_enabled"
        )?.isChecked ?: true
        updateSlot1AudioPrefsVisibility(slot1Enabled)

        val secondaryEnabled = findPreference<androidx.preference.SwitchPreferenceCompat>(
            "pref_secondary_notifications_enabled"
        )?.isChecked ?: true
        updateSecondaryAudioModeVisibility(secondaryEnabled)

        updateTTSCategoryVisibility()
    }

    /**
     * Show/hide TTS settings category based on audio mode and secondary notifications state
     * TTS category should be visible when:
     * 1. Slot 1 audio style is "tts" (TTS Voice) OR
     * 2. Secondary notifications are enabled AND audio mode is "tts" (TTS Voice) OR
     * 3. Secondary notifications are enabled AND audio mode is "tone_tts" (Tone + TTS)
     */
    private fun updateTTSCategoryVisibility() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val ttsCategory = findPreference<PreferenceCategory>("pref_tts_settings_category")

        // 1. Check Slot 1 (Favorite)
        val slot1AudioStyle = prefs.getString("pref_main_slot_audio_style", "azan")
        val isSlot1Tts = (slot1AudioStyle == "tts")

        // 2. Check Slots 2-4 (Secondary)
        val secondaryEnabled = prefs.getBoolean("pref_secondary_notifications_enabled", true)
        val secondaryMode = prefs.getString("pref_secondary_audio_mode", "tts")
        val isSecondaryTts = secondaryEnabled && (secondaryMode == "tts" || secondaryMode == "tone_tts")

        // 3. Combined Logic: Show if ANY slot needs TTS
        val shouldShowTTS = isSlot1Tts || isSecondaryTts

        ttsCategory?.isVisible = shouldShowTTS

        Log.d(TAG, "TTS Category Visibility: $shouldShowTTS " +
                "(Slot1=$slot1AudioStyle, SecondaryEnabled=$secondaryEnabled, SecondaryMode=$secondaryMode)")
    }

    /**
     * Set up TTS configuration preferences
     */
    private fun setupTTSSettings() {
        // TTS Language
        findPreference<ListPreference>("pref_tts_language")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val locale = parseLocaleFromValue(newValue as String)
                tts?.language = locale
                Log.d(TAG, "TTS language changed to: ${locale.displayName}")

                // ✅ Refresh voice list to match selected language
                populateVoiceListForLocale(locale)

                true
            }
        }

        // ✅ Deterministic voice selection (voice name)
        findPreference<ListPreference>("pref_tts_voice_name")?.apply {
            // Populate immediately based on currently selected language
            populateVoiceListForLocale(getCurrentTTSLanguage())

            setOnPreferenceChangeListener { _, newValue ->
                val selectedName = newValue as String
                Log.d(TAG, "TTS voice selected: $selectedName")

                // Bind preview engine directly to the selected Voice if available
                val engine = tts
                if (engine != null) {
                    val selected = engine.voices?.firstOrNull { it.name == selectedName }
                    if (selected != null) {
                        engine.voice = selected
                        Log.d(TAG, "Applied selected voice to preview engine: ${selected.name}")
                      } else {
                        Log.w(TAG, "Selected voice '$selectedName' no longer available")
                    }
                }

                true // persist via PreferencesDataStore
            }
        }

        // Remove deprecated gender-based preference wiring (no guessing)
        findPreference<ListPreference>("pref_tts_voice_gender")?.apply {
            // Keep the key around only if it still exists in XML; hide it.
            isVisible = false
        }

        // TTS Speed
        findPreference<SeekBarPreference>("pref_tts_speed")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val speed = (newValue as Int) / 100f
                tts?.setSpeechRate(speed)
                Log.d(TAG, "TTS speed changed to: $speed")
                true
            }
        }
    }

    /**
     * Populates the 'pref_tts_voice_name' ListPreference using only voices returned by tts.voices.
     *
     * Matching rules:
     * - First strict match (language + country)
     * - Fallback to language-only
     */
    private fun populateVoiceListForLocale(locale: Locale) {
        val voicePref = findPreference<ListPreference>("pref_tts_voice_name") ?: return
        val engine = tts ?: return
        // Initialization Guard: Only proceed if TTS is ready
        if (!isTtsReady) {
            Log.w(TAG, "populateVoiceListForLocale: TTS not ready, skipping population")
            voicePref.isEnabled = false
            return
        }
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) {
            Log.w(TAG, "populateVoiceListForLocale: no voices available yet")
            voicePref.entries = arrayOf("(No voices available)")
            voicePref.entryValues = arrayOf("")
            voicePref.isEnabled = false
            return
        }
        // Strict mapping: Only include voices with a clean display name
        val mappedVoices = voices.mapNotNull { v ->
            val cleanName = getCleanVoiceName(v.name)
            if (cleanName != null) v to cleanName else null
        }
        if (mappedVoices.isEmpty()) {
            Log.w(TAG, "populateVoiceListForLocale: no mapped voices found")
            voicePref.entries = arrayOf("(No mapped voices available)")
            voicePref.entryValues = arrayOf("")
            voicePref.isEnabled = false
            return
        }
        // Only show voices matching the selected locale (strict match or language match)
        val strict = mappedVoices.filter { it.first.locale == locale }
        val candidates = if (strict.isNotEmpty()) strict else mappedVoices.filter { it.first.locale.language == locale.language }
        if (candidates.isEmpty()) {
            Log.w(TAG, "populateVoiceListForLocale: no candidates for ${locale.toLanguageTag()}")
            voicePref.entries = arrayOf("(No voices for ${locale.displayName})")
            voicePref.entryValues = arrayOf("")
            voicePref.isEnabled = false
            return
        }
        // Sort by quality desc for nicer UX
        val sorted = candidates.sortedByDescending { it.first.quality }
        voicePref.isEnabled = true
        voicePref.entries = sorted.map { it.second }.toTypedArray() // Clean display names
        voicePref.entryValues = sorted.map { it.first.name }.toTypedArray() // System names
        // Only perform the 'still valid' check if TTS is ready and voices are available
        val currentValue = voicePref.value
        val stillValid = sorted.any { it.first.name == currentValue }
        if (!stillValid && sorted.isNotEmpty()) {
            val best = sorted.firstOrNull()?.first?.name
            if (!best.isNullOrBlank()) {
                Log.w(TAG, "Selected voice '$currentValue' not available for ${locale.toLanguageTag()}, defaulting to '$best'")
                voicePref.value = best
            }
        }
    }

    /**
     * Strict mapping: Only show voices explicitly approved in the mapping table.
     * Returns the clean display name if the systemName starts with a known key.
     * Returns null if the voice should be hidden from the UI.
     */
    private fun getCleanVoiceName(systemName: String): String? {
        val mapping = voiceDisplayMapping()
        for ((key, displayName) in mapping) {
            if (systemName.startsWith(key, ignoreCase = true)) {
                return displayName
            }
        }
        return null
    }

    /**
     * Hardcoded mapping based on the user's verified voice list.
     * key = original Voice.name prefix, value = displayed name
     */
    private fun voiceDisplayMapping(): Map<String, String> = mapOf(
        // English US
        "en-us-x-tpf-local" to "En-US Susan",
        "en-us-x-iom-local" to "En-US James",
        // English GB
        "en-gb-x-gbg-local" to "En-GB Emily",
        "en-gb-x-gbd-local" to "En-GB Thomas",
        // Arabic
        "ar-language" to "Ar-AR Mariam",
        "ar-xa-x-are-local" to "Ar-AR Omar",
        // Bengali
        "bn-IN-language" to "Bn-IN Ayesha",
        "bn-in-x-bnd-local" to "Bn-IN Karim",
        // French
        "fr-FR-language" to "Fr-FR Claire",
        "fr-fr-x-frb-local" to "Fr-FR Pierre",
        // French CA
        "fr-CA-language" to "Fr-CA Marie",
        "fr-ca-x-cad-local" to "Fr-CA André",
        // Hindi
        "hi-IN-language" to "Hi-IN Priya",
        "hi-in-x-hid-local" to "Hi-IN Raj",
        // Indonesian
        "id-id-x-idc-local" to "Id-ID Dewi",
        "id-id-x-idd-local" to "Id-ID Agus",
        // Turkish
        "tr-tr-x-cfs-local" to "Tr-TR Zeynep",
        "tr-tr-x-ama-local" to "Tr-TR Ahmet",
        // Urdu
        "ur-pk-x-cfn-local" to "Ur-PK Nadia",
        "ur-in-x-urb-local" to "Ur-IN Imran",
    )

    private fun setupInstallVoiceDataButton() {
        findPreference<Preference>("pref_tts_install_voice_data")?.apply {
            setOnPreferenceClickListener {
                try {
                    val engine = TTSAnnouncementEngine(requireContext().applicationContext)
                    val intent: Intent = engine.buildTtsSettingsIntent()
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // Very rare, but some OEM ROMs may not expose that explicit action.
                    Log.e(TAG, "TTS settings activity not found", e)
                    Toast.makeText(requireContext(), "TTS settings not available on this device", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open TTS settings", e)
                    Toast.makeText(requireContext(), "Failed to open TTS settings: ${e.message}", Toast.LENGTH_LONG).show()
                }
                true
            }
        }
    }

    /**
     * Set up the "Test TTS" button
     */
    private fun setupTestTTSButton() {
        findPreference<Preference>("pref_test_tts")?.apply {
            setOnPreferenceClickListener {
                // Direct proof the UI click is firing (requested)
                Log.wtf("TTS_TEST", "Direct UI Click")
                testTTS()
                true
            }
        }
    }

    /**
     * Test TTS with sample text
     * Plays attention tone (if enabled) followed by voice announcement
     */
    private fun testTTS() {
        if (!ttsInitialized) {
            Toast.makeText(requireContext(), "TTS engine not ready. Please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val attentionToneEnabled = sharedPrefs.getBoolean("pref_attention_tone_before_tts", true)

        lifecycleScope.launch {
            try {
                if (attentionToneEnabled) {
                    Log.d(TAG, "🔔 [Attention tone logic would execute here]")
                    delay(500)
                }

                val language = getCurrentTTSLanguage()
                val speed = getCurrentTTSSpeed()
                val testMessage = getTranslatedTestMessage(language)

                val engine = tts ?: run {
                    Toast.makeText(requireContext(), "TTS engine not ready.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // ✅ Use selected locale and selected Voice.name deterministically
                val voiceName = sharedPrefs.getString("pref_tts_voice_name", null)

                engine.setSpeechRate(speed)

                val langResult = engine.setLanguage(language)
                if (langResult < TextToSpeech.LANG_AVAILABLE) {
                    Log.e(TAG, "❌ Language ${language.language} unavailable for preview")
                    Toast.makeText(requireContext(), "Language not found. Check 'Install Voice Data'.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Wait a bit for Android 11 to load voices
                delay(150)

                // Apply the exact selected voice if still available; otherwise fallback to best-quality candidate.
                val voices = engine.voices.orEmpty()
                val chosen = voiceName?.let { pref -> voices.firstOrNull { it.name == pref } }
                    ?: run {
                        val strict = voices.filter { it.locale == language }
                        val candidates = if (strict.isNotEmpty()) strict else voices.filter { it.locale.language == language.language }
                        candidates.maxByOrNull { it.quality }
                    }

                if (chosen != null) {
                    engine.voice = chosen
                    if (voiceName != null && chosen.name != voiceName) {
                        Toast.makeText(requireContext(), "Preferred voice not available; using default.", Toast.LENGTH_SHORT).show()
                    }
                    Log.d(TAG, "Test TTS using voice: ${chosen.name}")
                }

                delay(150)

                Log.d(TAG, "🗣️ Speaking: $testMessage")

                @Suppress("DEPRECATION")
                val result = engine.speak(testMessage, TextToSpeech.QUEUE_FLUSH, null)

                if (result == TextToSpeech.ERROR) {
                    Toast.makeText(requireContext(), "Error playing TTS", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Playing TTS preview...", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error testing TTS", e)
                Toast.makeText(requireContext(), "TTS test failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER METHODS - TTS Configuration
    // ══════════════════════════════════════════════════════════════

    /**
     * Get current TTS language from preferences
     */
    private fun getCurrentTTSLanguage(): Locale {
        val languageCode = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .getString("pref_tts_language", "en_US") ?: "en_US"
        return parseLocaleFromValue(languageCode)
    }

    /**
     * Get current TTS speed from preferences
     */
    private fun getCurrentTTSSpeed(): Float {
        val speedPercent = PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .getInt("pref_tts_speed", 100)
        return speedPercent / 100f
    }

    /**
     * Parse locale from preference value (e.g., "en_US" → Locale.US)
     */
    private fun parseLocaleFromValue(value: String): Locale {
        return when (value) {
            "en_US" -> Locale.US
            "en_GB" -> Locale.UK
            "ar" -> Locale("ar")
            "bn" -> Locale("bn") // Bengali
            "fr" -> Locale.FRENCH
            "hi" -> Locale("hi") // Hindi
            "in", "id" -> Locale("id") // Indonesian (handles both legacy "in" and modern "id")
            "fa" -> Locale("fa") // Persian/Farsi
            "tr" -> Locale("tr") // Turkish
            "ur" -> Locale("ur") // Urdu
            else -> Locale.US
        }
    }

    /**
     * Get translated test message based on selected language
     * Returns proper translation so TTS speaks in the correct language
     */
    private fun getTranslatedTestMessage(locale: Locale): String {
        val languageCode = locale.language
        Log.d(TAG, "📝 Getting translation for language: $languageCode (Locale: ${locale.displayName})")

        val message = when (languageCode) {
            "en" -> "This is a test of the Fajr notification for your secondary mosques."

            "ar" -> "هذا اختبار لإشعار صلاة الفجر للمساجد الثانوية الخاصة بك"

            "bn" -> "এটি আপনার গৌণ মসজিদের জন্য ফজরের বিজ্ঞপ্তির একটি পরীক্ষা।"

            "fr" -> "Ceci est un test de la notification Fajr pour vos mosquées secondaires."

            "hi" -> "यह आपकी द्वितीयक मस्जिदों के लिए फज्र अधिसूचना का एक परीक्षण है।"

            "in", "id" -> "Ini adalah tes notifikasi Fajr untuk masjid sekunder Anda."

            "fa" -> "این یک آزمایش اعلان نماز صبح برای مساجد ثانویه شماست."

            "tr" -> "Bu, ikincil camileriniz için Sabah namazı bildiriminin bir testidir."

            "ur" -> "یہ آپ کی ثانوی مساجد کے لیے فجر کی اطلاع کا ایک ٹیسٹ ہے۔"

            else -> {
                Log.w(TAG, "⚠️ No translation for language: $languageCode, using English")
                "This is a test of the Fajr notification for your secondary mosques."
            }
        }

        Log.d(TAG, "📝 Selected message: $message")
        return message
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE - TTS Cleanup
    // ══════════════════════════════════════════════════════════════

    override fun onDestroy() {
        super.onDestroy()

        // Shut down TTS engine
        tts?.stop()
        tts?.shutdown()
        tts = null

        Log.d(TAG, "TTS engine shut down")
    }
}
