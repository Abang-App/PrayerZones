package com.abang.prayerzones.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abang.prayerzones.data.mosquePreferencesDataStore
import com.abang.prayerzones.model.JumuahInfo
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.NotificationType
import com.abang.prayerzones.model.initialMosques
import com.abang.prayerzones.util.AlarmScheduler
import com.abang.prayerzones.util.OfflinePrayerCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing user's selected mosques using DataStore
 *
 * Slot Management:
 * - Slot 0: GPS nearest mosque (protected, can only be set via saveNearestMosque)
 * - Slots 1-3: User-selected mosques (can be added/removed freely)
 */
@Singleton
class MosqueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmScheduler: AlarmScheduler
) {
    // ✅ Use singleton DataStore from PreferencesManager to prevent multiple instances
    private val dataStore: DataStore<Preferences>
        get() = context.mosquePreferencesDataStore

    companion object {
        private const val TAG = "MosqueRepository"

        // DataStore keys for the 4 slots (0-3)
        private val SLOT_0_KEY = stringPreferencesKey("mosque_slot_0") // GPS nearest
        private val SLOT_1_KEY = stringPreferencesKey("mosque_slot_1") // User selected
        private val SLOT_2_KEY = stringPreferencesKey("mosque_slot_2") // User selected
        private val SLOT_3_KEY = stringPreferencesKey("mosque_slot_3") // User selected

        // Helper to get key by index
        private fun getKeyForSlot(slotIndex: Int): Preferences.Key<String> {
            return when (slotIndex) {
                0 -> SLOT_0_KEY
                1 -> SLOT_1_KEY
                2 -> SLOT_2_KEY
                3 -> SLOT_3_KEY
                else -> throw IllegalArgumentException("Slot index must be 0-3, got $slotIndex")
            }
        }

        private fun timeZoneKeyForMosque(mosqueId: String): Preferences.Key<String> =
            stringPreferencesKey("mosque_tz_$mosqueId")

        // Per-mosque per-prayer mute key: true means muted/disabled.
        private fun prayerMuteKey(mosqueId: String, prayerKey: String): Preferences.Key<Boolean> =
            androidx.datastore.preferences.core.booleanPreferencesKey("mute_${mosqueId}_${prayerKey}")
    }

    private fun applyStoredTimeZone(mosque: Mosque?, preferences: Preferences): Mosque? {
        if (mosque == null) return null
        val storedTz = preferences[timeZoneKeyForMosque(mosque.id)]?.trim().orEmpty()
        if (storedTz.isBlank()) return mosque
        val zoneOk = runCatching { java.time.ZoneId.of(storedTz) }.isSuccess
        if (!zoneOk) return mosque
        return if (mosque.timeZone != storedTz) mosque.copy(timeZone = storedTz) else mosque
    }

    // ========================================
    // CORE FLOW: Selected Mosques (Reactive)
    // ========================================

    /**
     * Main Flow exposing the list of selected mosques (up to 4)
     * IMPORTANT: Returns List<Mosque?> to maintain slot positions
     * Nulls indicate empty slots - DO NOT filter them out
     *
     * Order: [Slot 0, Slot 1, Slot 2, Slot 3] (with nulls for empty slots)
     */
    val selectedMosques: Flow<List<Mosque?>> = dataStore.data.map { preferences ->
        val mosques = mutableListOf<Mosque?>()

        for (slotIndex in 0..3) {
            val mosqueId = preferences[getKeyForSlot(slotIndex)]
            if (mosqueId != null) {
                val base = initialMosques.find { it.id == mosqueId }
                if (base != null) {
                    mosques.add(applyStoredTimeZone(base, preferences))
                } else {
                    Log.w(TAG, "Mosque ID '$mosqueId' not found in initialMosques")
                    mosques.add(null)
                }
            } else {
                mosques.add(null)
            }
        }

        Log.d(TAG, "selectedMosques Flow emitted: ${mosques.filterNotNull().size} filled, ${mosques.count { it == null }} empty slots")
        mosques
    }

    /**
     * Flow for a specific slot (useful for individual slot monitoring)
     */
    fun getMosqueForSlot(slotIndex: Int): Flow<Mosque?> {
        require(slotIndex in 0..3) { "Slot index must be 0-3" }

        return dataStore.data.map { preferences ->
            val mosqueId = preferences[getKeyForSlot(slotIndex)]
            val base = mosqueId?.let { id -> initialMosques.find { it.id == id } }
            applyStoredTimeZone(base, preferences)
        }
    }

    /**
     * Flow exposing IDs of all currently selected mosques (for duplicate filtering)
     * Returns Set<String> of mosque IDs across all 4 slots
     */
    val selectedMosqueIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        val ids = mutableSetOf<String>()
        for (slotIndex in 0..3) {
            val mosqueId = preferences[getKeyForSlot(slotIndex)]
            if (mosqueId != null) {
                ids.add(mosqueId)
            }
        }
        ids
    }

    // ========================================
    // WRITE OPERATIONS
    // ========================================

    /**
     * Save a mosque to a specific slot (1-3 only)
     * Slot 0 is reserved for GPS nearest mosque - use saveNearestMosque() instead
     *
     * @param slotIndex Must be 1, 2, or 3
     * @param mosqueId The ID of the mosque to save
     * @throws IllegalArgumentException if slotIndex is 0 or out of range
     */
    suspend fun saveMosqueToSlot(slotIndex: Int, mosqueId: String) {
        require(slotIndex in 1..3) {
            "Cannot manually save to slot $slotIndex. Slots 1-3 are for user selection. Use saveNearestMosque() for slot 0."
        }

        Log.d(TAG, "Saving mosque '$mosqueId' to slot $slotIndex")

        dataStore.edit { preferences ->
            preferences[getKeyForSlot(slotIndex)] = mosqueId
        }
    }

    /**
     * Save the GPS nearest mosque to Slot 0 AND de-duplicate it from slots 1-3.
     *
     * IMPORTANT:
     * - Does NOT shift other mosques.
     * - If duplicates are found in slots 1-3, those slots become empty.
     */
    suspend fun saveNearestMosqueWithDeDup(nearestMosqueId: String?) {
        Log.d(TAG, "Saving nearest mosque (with dedup) to slot 0: $nearestMosqueId")

        dataStore.edit { preferences ->
            if (nearestMosqueId != null) {
                preferences[SLOT_0_KEY] = nearestMosqueId

                // De-dup: if same mosque exists in slots 1-3, remove it.
                for (slotIndex in 1..3) {
                    val key = getKeyForSlot(slotIndex)
                    if (preferences[key] == nearestMosqueId) {
                        preferences.remove(key)
                    }
                }
            } else {
                preferences.remove(SLOT_0_KEY)
            }
        }
    }

    suspend fun saveNearestMosque(mosqueId: String?) {
        // Keep backward compatible API but enforce de-dup rules.
        saveNearestMosqueWithDeDup(mosqueId)
    }

    /**
     * Remove a mosque from a specific slot (1-3 only)
     * Slot 0 cannot be manually removed - it's managed by GPS updates
     *
     * IMPORTANT: This also cancels ALL alarms associated with the removed mosque
     * to prevent "ghost notifications"
     *
     * @param slotIndex Must be 1, 2, or 3
     * @throws IllegalArgumentException if slotIndex is 0 or out of range
     */
    suspend fun removeMosqueFromSlot(slotIndex: Int) {
        require(slotIndex in 1..3) {
            "Cannot manually remove slot $slotIndex. Slots 1-3 are for user selection. Slot 0 is GPS-managed."
        }

        // ✅ CRITICAL: Get the mosque ID BEFORE removing it, so we can cancel its alarms
        val mosqueIdToRemove: String? = try {
            dataStore.data.first()[getKeyForSlot(slotIndex)]
        } catch (_: Throwable) {
            null
        }

        Log.d(TAG, "Removing mosque from slot $slotIndex (ID: $mosqueIdToRemove)")

        // ✅ Cancel all alarms for this mosque BEFORE removing from DataStore
        if (mosqueIdToRemove != null) {
            alarmScheduler.cancelAllAlarmsForMosque(mosqueIdToRemove)
            Log.d(TAG, "✅ Cancelled all alarms for removed mosque: $mosqueIdToRemove")
        }

        // Now remove from DataStore
        dataStore.edit { preferences ->
            preferences.remove(getKeyForSlot(slotIndex))
        }
    }

    /**
     * Clear all user-selected slots (1-3)
     * Slot 0 (GPS nearest) is NOT cleared
     */
    suspend fun clearUserSelectedMosques() {
        Log.d(TAG, "Clearing all user-selected mosques (slots 1-3)")

        dataStore.edit { preferences ->
            preferences.remove(SLOT_1_KEY)
            preferences.remove(SLOT_2_KEY)
            preferences.remove(SLOT_3_KEY)
        }
    }

    /**
     * Clear ALL slots including GPS nearest (Slot 0)
     * Use with caution - typically you only want clearUserSelectedMosques()
     */
    suspend fun clearAllSlots() {
        Log.d(TAG, "Clearing ALL mosque slots (0-3)")

        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    // ========================================
    // QUERY OPERATIONS
    // ========================================

    /**
     * Find the first empty slot in range 1-3
     * Returns null if all slots are occupied
     * Slot 0 is never returned (it's GPS-managed)
     */
    suspend fun findFirstEmptySlot(): Int? {
        val prefs = dataStore.data.first()
        for (i in 1..3) {
            if (prefs[getKeyForSlot(i)] == null) return i
        }
        return null
    }

    /**
     * Check if a mosque is already selected in any slot
     * Useful to prevent duplicates
     */
    suspend fun isMosqueAlreadySelected(mosqueId: String): Boolean {
        val prefs = dataStore.data.first()
        for (i in 0..3) {
            if (prefs[getKeyForSlot(i)] == mosqueId) return true
        }
        return false
    }

    /**
     * Find which slot contains a specific mosque
     * Returns null if mosque is not in any slot
     */
    suspend fun findSlotForMosque(mosqueId: String): Int? {
        val prefs = dataStore.data.first()
        for (i in 0..3) {
            if (prefs[getKeyForSlot(i)] == mosqueId) return i
        }
        return null
    }

    /**
     * Static debug mosque definitions with realistic metadata for stress testing.
     */
    internal val initialMosquesTest = listOf(
        Mosque(
            id = "SG009",
            name = "Al-Khair Mosque 1",
            displayCity = "Singapore",
            displayCountry = "Singapore",
            apiCity = "Singapore",
            apiCountry = "Singapore",
            channelId = "",
            notifType = NotificationType.AZAN,
            fajrSound = "fajr.mp3",
            duhaSound = "duha.mp3",
            dhuhrSound = "dhuhr.mp3",
            asrSound = "asr.mp3",
            maghribSound = "maghrib.mp3",
            ishaSound = "isha.mp3",
            timeZone = "Asia/Singapore",
            latitude = 1.3826,
            longitude = 103.7499,
            jumuah = JumuahInfo(session1 = null, session2 = "14:00"),
            defaultTimings = null // Populated dynamically by generateSpacedTimings
        ),
        Mosque(
            id = "SA009",
            name = "Masjid Al-Nabawi 1",
            displayCity = "Madinah",
            displayCountry = "Saudi Arabia",
            apiCity = "Madinah",
            apiCountry = "Saudi Arabia",
            channelId = "UCROKYPep-UuODNwyipe6JMw", // ✅ Real Al-Nabawi channel
            notifType = NotificationType.TTS,
            fajrSound = "fajr.mp3",
            duhaSound = "duha.mp3",
            dhuhrSound = "dhuhr.mp3",
            asrSound = "asr.mp3",
            maghribSound = "maghrib.mp3",
            ishaSound = "isha.mp3",
            timeZone = "Asia/Riyadh",
            latitude = 24.468333,
            longitude = 39.610833,
            jumuah = JumuahInfo(session1 = "12:35"),
            defaultTimings = null // Populated dynamically by generateSpacedTimings
        )
    )

    /**
     * Active mosques for runtime systems (Pager/Alarms): never emits null/empty slots.
     *
     * - Preserves slot order (0..3)
     * - DOES NOT fill empty slots.
     * - ✅ INJECTS STRESS TEST MOSQUE at top if DEBUG_MODE_ENABLED is true
     */
    val activeMosques: Flow<List<Mosque>> = selectedMosques.map { slotList ->
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val debugModeEnabled = prefs.getBoolean("DEBUG_MODE_ENABLED", false)

        if (debugModeEnabled) {
            // ✅ PRIORITY SWAPPING: Swap START TIMES only, NOT mosque objects
            // Priority 0 = Local/SG fires first (default)
            // Priority 1 = Remote/Riyadh fires first
            val priority = prefs.getInt("DEBUG_TEST_PRIORITY", 0)

            // 1. Retrieve or Create the Anchor (Keep existing logic)
            val anchorKey = "DEBUG_BASE_TIME_SG"
            val anchoredBaseMillis = prefs.getLong(anchorKey, 0L)
            val anchorInstant = if (anchoredBaseMillis > 0L) {
                java.time.Instant.ofEpochMilli(anchoredBaseMillis)
            } else {
                // Create new anchor: Now (SG) rounded + 2 mins
                val newBase = ZonedDateTime.now(ZoneId.of("Asia/Singapore"))
                    .withSecond(0).withNano(0)
                    .plusMinutes(2)
                val instant = newBase.toInstant()
                prefs.edit().putLong(anchorKey, instant.toEpochMilli()).apply()
                Log.w(TAG, "🔧 DEBUG MODE: Created new anchor instant=${java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)}")
                instant
            }

            // 2. Define the two timelines (Instants)
            val instantImmediate = anchorInstant
            val instantDelayed = anchorInstant.plusSeconds(26 * 60) // +26 mins

            // 3. Apply Priority Logic - Assign Instants to Slots (The Swap)
            val slot0_TargetInstant = if (priority == 0) instantImmediate else instantDelayed
            val slot1_TargetInstant = if (priority == 0) instantDelayed else instantImmediate

            // 4. Convert Instants to Local ZonedDateTimes for the Generator
            // CRITICAL: Must convert to the specific mosque's zone so the generated string "HH:mm"
            // matches the wall-clock time in that city corresponding to the target instant.
            val base0 = slot0_TargetInstant.atZone(ZoneId.of("Asia/Singapore"))
            val base1 = slot1_TargetInstant.atZone(ZoneId.of("Asia/Riyadh"))

            Log.w(TAG, if (priority == 0) {
                "🔧 DEBUG PRIORITY 0: Local/SG fires FIRST (Immediate), Remote/Riyadh SECOND (+26m)"
            } else {
                "🔧 DEBUG PRIORITY 1: Local/SG fires SECOND (+26m), Remote/Riyadh FIRST (Immediate)"
            })

            // 5. Generate timings for FIXED mosque objects (NO SWAPPING of mosque objects)
            // Slot0 is ALWAYS SG009 (initialMosquesTest[0])
            // Slot1 is ALWAYS SA009 (initialMosquesTest[1])
            val slot0 = initialMosquesTest[0].copy(
                defaultTimings = generateSpacedTimings(base = base0, minutesBetween = 4L)
            )
            val slot1 = initialMosquesTest[1].copy(
                defaultTimings = generateSpacedTimings(base = base1, minutesBetween = 4L)
            )

            Log.w(TAG, "🔧 DEBUG MODE ACTIVE: returning realistic mock mosques (ANCHORED)")
            Log.w(TAG, "  Anchor Instant: ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(anchorInstant)}")
            Log.w(TAG, "  Slot0 (${slot0.id} FIXED): base=$base0, timings=${slot0.defaultTimings}")
            Log.w(TAG, "  Slot1 (${slot1.id} FIXED): base=$base1, timings=${slot1.defaultTimings}")

            listOf(slot0, slot1)
        } else {
            // Normal mode: clear the anchor
            if (prefs.contains("DEBUG_BASE_TIME_SG")) {
                prefs.edit().remove("DEBUG_BASE_TIME_SG").apply()
                Log.d(TAG, "Cleared DEBUG_BASE_TIME_SG anchor")
            }

            // Preserve slot order, drop nulls, and hydrate timings
            val rawList = slotList.filterNotNull().distinctBy { it.id }

            // Hydrate: Calculate prayer times for mosques without defaultTimings
            rawList.map { mosque ->
                if (mosque.defaultTimings != null) {
                    // Already has timings (e.g., from test mode or manual override)
                    mosque
                } else {
                    // Calculate timings using Adhan2 library
                    val calculated = OfflinePrayerCalculator.calculateToday(mosque)
                    Log.d(TAG, "Hydrated timings for ${mosque.id}: $calculated")
                    mosque.copy(defaultTimings = calculated)
                }
            }
        }
    }

    private fun generateSpacedTimings(
        base: ZonedDateTime,
        names: List<String> = listOf("Fajr","Duha","Dhuhr","Asr","Maghrib","Isha"),
        minutesBetween: Long = 4L,
        fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    ): Map<String, String> {
        val normalizedBase = base.withSecond(0).withNano(0)
        return names.mapIndexed { i, name ->
            name to normalizedBase.plusMinutes(i * minutesBetween).format(fmt)
        }.toMap()
    }

    /**
     * Persist the API-provided canonical timezone for a mosque.
     * This is real persistence (DataStore) so cold-start alarm install reads it.
     */
    suspend fun updateMosqueTimeZone(mosqueId: String, timeZone: String) {
        val normalized = timeZone.trim()
        if (normalized.isBlank()) return
        val zoneOk = runCatching { java.time.ZoneId.of(normalized) }.isSuccess
        if (!zoneOk) {
            Log.w(TAG, "updateMosqueTimeZone: invalid zone '$normalized' for mosqueId=$mosqueId")
            return
        }

        dataStore.edit { prefs ->
            prefs[timeZoneKeyForMosque(mosqueId)] = normalized
        }

        Log.d(TAG, "Persisted mosque timeZone: $mosqueId -> $normalized")
    }

    /**
     * Observe the mute map for a specific mosque.
     * Key is prayerKey (e.g., Fajr/Dhuhr/Maghrib). Value=true means muted.
     */
    fun observePrayerMuteStates(mosqueId: String): Flow<Map<String, Boolean>> {
        return dataStore.data.map { prefs ->
            buildMap {
                // We only care about the 6 keys we schedule/display.
                val prayers = listOf("Fajr", "Duha", "Dhuhr", "Asr", "Maghrib", "Isha")
                for (p in prayers) {
                    put(p, prefs[prayerMuteKey(mosqueId, p)] ?: false)
                }
            }
        }
    }

    /**
     * Read the mute value for a single mosque+prayer.
     */
    suspend fun isPrayerMuted(mosqueId: String, prayerKey: String): Boolean {
        val prefs = dataStore.data.first()
        return prefs[prayerMuteKey(mosqueId, prayerKey)] ?: false
    }

    /**
     * Persist the mute state for a mosque+prayer. Used by main-screen bell icons.
     */
    suspend fun setPrayerMuted(mosqueId: String, prayerKey: String, muted: Boolean) {
        dataStore.edit { prefs ->
            val key = prayerMuteKey(mosqueId, prayerKey)
            if (muted) {
                prefs[key] = true
            } else {
                prefs.remove(key)
            }
        }
    }

    private fun isSingaporeMosqueId(mosqueId: String): Boolean = mosqueId.startsWith("SG", ignoreCase = true)

    private suspend fun hasAnySingaporeMosqueSelected(exceptSlot: Int? = null): Boolean {
        val prefs = dataStore.data.first()
        for (slot in 0..3) {
            if (exceptSlot != null && slot == exceptSlot) continue
            val id = prefs[getKeyForSlot(slot)]
            if (id != null && isSingaporeMosqueId(id)) return true
        }
        return false
    }

    /**
     * Save a mosque to slots 1-3 with the Singapore single-selection rule.
     * @return true if saved, false if blocked by SG rule.
     */
    suspend fun saveMosqueToSlotEnforcingSingaporeRule(slotIndex: Int, mosqueId: String): Boolean {
        require(slotIndex in 1..3) { "Can only save to slots 1-3" }

        if (isSingaporeMosqueId(mosqueId) && hasAnySingaporeMosqueSelected(exceptSlot = slotIndex)) {
            Log.w(TAG, "Blocked adding second SG mosque to slot $slotIndex: $mosqueId")
            return false
        }

        saveMosqueToSlot(slotIndex, mosqueId)
        return true
    }

    /**
     * Save nearest mosque to slot 0, but enforce Singapore single-selection rule across all slots.
     * @return true if saved, false if blocked by SG rule.
     */
    suspend fun saveNearestMosqueWithDeDupEnforcingSingaporeRule(nearestMosqueId: String?): Boolean {
        if (nearestMosqueId == null) {
            saveNearestMosqueWithDeDup(null)
            return true
        }

        if (isSingaporeMosqueId(nearestMosqueId) && hasAnySingaporeMosqueSelected(exceptSlot = 0)) {
            Log.w(TAG, "Blocked slot0 update to SG mosque because SG already selected in other slot(s): $nearestMosqueId")
            return false
        }

        saveNearestMosqueWithDeDup(nearestMosqueId)
        return true
    }
}
