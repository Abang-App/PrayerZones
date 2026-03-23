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
 * Handles cleanup of "ghost" alarms from removed mosques
 *
 * Problem: When a mosque is removed from the app, its AlarmManager alarms persist
 * in the system unless explicitly cancelled. This can lead to notifications from
 * mosques that are no longer in the user's selected list.
 *
 * Solution: On app startup, compare all possible mosque IDs with currently selected
 * mosques and cancel alarms for any mosques that are no longer selected.
 */
@Singleton
class AlarmCleanupHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mosqueRepository: MosqueRepository,
    private val alarmScheduler: AlarmScheduler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AlarmCleanupHelper"
    }

    /**
     * Cleanup orphaned alarms from mosques that are no longer selected
     * Call this from Application onCreate or MainActivity onCreate
     *
     * This runs asynchronously and won't block the UI thread
     */
    fun cleanupOrphanedAlarms() {
        scope.launch {
            try {
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "Starting Ghost Alarm Cleanup")
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Get currently selected mosque IDs
                val selectedMosques = mosqueRepository.selectedMosques.first()
                val selectedMosqueIds = selectedMosques.filterNotNull().map { it.id }.toSet()

                Log.d(TAG, "Currently selected mosques: ${selectedMosqueIds.size}")
                selectedMosqueIds.forEach { id ->
                    Log.d(TAG, "  ✓ Active: $id")
                }

                // Get all possible mosque IDs from the master list
                val allMosqueIds = initialMosques.map { it.id }.toSet()

                // Find mosques that are NOT selected but might have alarms
                val orphanedMosqueIds = allMosqueIds - selectedMosqueIds

                if (orphanedMosqueIds.isEmpty()) {
                    Log.d(TAG, "✅ No orphaned alarms found - all clean!")
                } else {
                    Log.d(TAG, "Found ${orphanedMosqueIds.size} mosques with potential ghost alarms:")
                    orphanedMosqueIds.forEach { id ->
                        Log.d(TAG, "  🧹 Cleaning: $id")
                    }

                    // Cancel all alarms for orphaned mosques
                    orphanedMosqueIds.forEach { mosqueId ->
                        alarmScheduler.cancelAllAlarmsForMosque(mosqueId)
                    }

                    Log.d(TAG, "✅ Ghost alarm cleanup completed successfully")
                }

                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during alarm cleanup", e)
            }
        }
    }

    /**
     * Force cleanup of ALL alarms (nuclear option)
     * Use this if you suspect the system has accumulated many ghost alarms
     * and you want to start fresh
     */
    fun nukeAllAlarms() {
        scope.launch {
            try {
                Log.w(TAG, "⚠️ NUCLEAR OPTION DISABLED: Not cancelling alarms automatically")
                Log.w(TAG, "Use cancelAlarmsForMosque(mosqueId) when a user removes a mosque.")
                // Intentionally no-op.
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during nuclear alarm cleanup", e)
            }
        }
    }
}
