package com.abang.prayerzones.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.repository.MosqueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class PrayerViewModel @Inject constructor(
    application: Application,
    private val mosqueRepository: MosqueRepository
) : AndroidViewModel(application) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    private val debugModeEnabledFlow: StateFlow<Boolean> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == "DEBUG_MODE_ENABLED") {
                trySend(sharedPrefs.getBoolean("DEBUG_MODE_ENABLED", false))
            }
        }
        trySend(prefs.getBoolean("DEBUG_MODE_ENABLED", false))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.getBoolean("DEBUG_MODE_ENABLED", false))

    // Manual refresh trigger; allows MainActivity.onResume() to force recomputation.
    private val refreshTrigger = MutableStateFlow(0)

    // Reactive UI state: combines activeMosques with debug flag and refreshTrigger.
    val uiState: StateFlow<UiState> = combine(
        mosqueRepository.activeMosques,
        debugModeEnabledFlow,
        refreshTrigger
    ) { mosques: List<Mosque>, debugEnabled: Boolean, _ ->
        UiState.Success(mosques = mosques, debugModeEnabled = debugEnabled)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UiState.Loading
        )

    // Current page index for the PrayerPager.
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    fun refreshAllData() {
        // Bump the trigger; combine() will re-emit immediately.
        refreshTrigger.update { it + 1 }
    }

    fun isDebugModeEnabledNow(): Boolean = prefs.getBoolean("DEBUG_MODE_ENABLED", false)

    fun onPageChanged(page: Int) {
        _currentPage.value = page
    }
}
