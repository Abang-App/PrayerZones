package com.abang.prayerzones.util

import android.content.Context
import android.content.SharedPreferences
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.initialMosques
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages selected mosque preferences
 * Slot 0: Always the nearest mosque (GPS-based, managed dynamically)
 * Slots 1-3: User-selected mosques
 */
@Singleton
class MosquePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "mosque_preferences",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_SLOT_1 = "selected_mosque_slot_1"
        private const val KEY_SLOT_2 = "selected_mosque_slot_2"
        private const val KEY_SLOT_3 = "selected_mosque_slot_3"
    }

    /**
     * Save a mosque ID to a specific slot (1-3)
     */
    fun saveMosqueToSlot(slot: Int, mosqueId: String?) {
        require(slot in 1..3) { "Slot must be between 1 and 3" }

        val key = when (slot) {
            1 -> KEY_SLOT_1
            2 -> KEY_SLOT_2
            3 -> KEY_SLOT_3
            else -> throw IllegalArgumentException("Invalid slot: $slot")
        }

        prefs.edit().apply {
            if (mosqueId != null) {
                putString(key, mosqueId)
            } else {
                remove(key)
            }
            apply()
        }
    }

    /**
     * Get mosque ID from a specific slot (1-3)
     */
    fun getMosqueIdFromSlot(slot: Int): String? {
        require(slot in 1..3) { "Slot must be between 1 and 3" }

        val key = when (slot) {
            1 -> KEY_SLOT_1
            2 -> KEY_SLOT_2
            3 -> KEY_SLOT_3
            else -> throw IllegalArgumentException("Invalid slot: $slot")
        }

        return prefs.getString(key, null)
    }

    /**
     * Get all selected mosque IDs (slots 1-3)
     */
    fun getAllSelectedMosqueIds(): List<String?> {
        return listOf(
            getMosqueIdFromSlot(1),
            getMosqueIdFromSlot(2),
            getMosqueIdFromSlot(3)
        )
    }

    /**
     * Clear a specific slot
     */
    fun clearSlot(slot: Int) {
        saveMosqueToSlot(slot, null)
    }

    /**
     * Clear all slots
     */
    fun clearAllSlots() {
        prefs.edit().apply {
            remove(KEY_SLOT_1)
            remove(KEY_SLOT_2)
            remove(KEY_SLOT_3)
            apply()
        }
    }

    /**
     * Get the full list of selected mosques (including nearest mosque at slot 0)
     * @param nearestMosque The GPS-based nearest mosque for slot 0
     * @return List of up to 4 mosques
     */
    fun getSelectedMosques(nearestMosque: Mosque?): List<Mosque> {
        val result = mutableListOf<Mosque>()

        // Slot 0: Nearest mosque (always first if available)
        nearestMosque?.let { result.add(it) }

        // Slots 1-3: User-selected mosques
        getAllSelectedMosqueIds().forEach { mosqueId ->
            if (mosqueId != null) {
                // Find mosque in initialMosques list
                initialMosques.find { it.id == mosqueId }?.let { mosque ->
                    // Avoid duplicates (in case nearest mosque is also selected manually)
                    if (!result.any { it.id == mosque.id }) {
                        result.add(mosque)
                    }
                }
            }
        }

        return result
    }
}

