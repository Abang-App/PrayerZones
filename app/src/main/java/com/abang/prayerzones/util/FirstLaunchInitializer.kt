package com.abang.prayerzones.util

import android.content.Context
import android.util.Log
import com.abang.prayerzones.model.initialMosques
import com.abang.prayerzones.repository.MosqueRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes default mosques on first app launch
 * Ensures users see prayer times immediately without needing to configure slots
 */
@Singleton
class FirstLaunchInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mosqueRepository: MosqueRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val PREFS_NAME = "first_launch"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val TAG = "FirstLaunchInitializer"
    }

    /**
     * Initialize default mosques if this is the first app launch
     * Call this from Application onCreate or MainActivity onCreate
     */
    fun initializeIfNeeded() {
        scope.launch {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isFirstLaunch = prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)

            if (isFirstLaunch) {
                Log.d(TAG, "First launch detected - initializing default mosques")
                initializeDefaultMosques()

                // Mark as initialized
                prefs.edit().putBoolean(KEY_IS_FIRST_LAUNCH, false).apply()
                Log.d(TAG, "First launch initialization complete")
            } else {
                Log.d(TAG, "Not first launch - skipping initialization")
            }
        }
    }

    /**
     * Initialize with the first 4 mosques from initialMosques
     * Slot 0: First mosque (will be replaced by GPS nearest when available)
     * Slots 1-3: Next 3 mosques
     */
    private suspend fun initializeDefaultMosques() {
        try {
            // Check if there are already mosques selected
            val existingMosques = mosqueRepository.selectedMosques.first()
            val hasAnySelected = existingMosques.any { it != null }
            if (hasAnySelected) {
                Log.d(TAG, "Mosques already configured - skipping initialization")
                return
            }

            // Add first 4 mosques as defaults
            val defaultMosques = initialMosques.take(4)

            if (defaultMosques.isNotEmpty()) {
                // Slot 0: First mosque (will be GPS nearest placeholder)
                mosqueRepository.saveNearestMosque(defaultMosques[0].id)
                Log.d(TAG, "Initialized slot 0: ${defaultMosques[0].name}")
            }

            // Slots 1-3: Additional mosques
            defaultMosques.drop(1).forEachIndexed { index, mosque ->
                val slotIndex = index + 1 // Slots 1, 2, 3
                if (slotIndex <= 3) {
                    mosqueRepository.saveMosqueToSlot(slotIndex, mosque.id)
                    Log.d(TAG, "Initialized slot $slotIndex: ${mosque.name}")
                }
            }

            Log.d(TAG, "Default mosques initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default mosques", e)
        }
    }
}
