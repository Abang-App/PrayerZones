package com.abang.prayerzones.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for in-app playback UI state.
 *
 * Owned by the playback service lifecycle:
 * - AzanPlaybackService sets isAzanPlaying=true when it starts.
 * - AzanPlaybackService sets isAzanPlaying=false when it stops/destroys.
 *
 * UI observes the StateFlow to show/hide the "Azan Playing" card.
 */
object PlaybackState {
    private val _isAzanPlaying = MutableStateFlow(false)
    val isAzanPlaying: StateFlow<Boolean> = _isAzanPlaying.asStateFlow()

    private val _currentPrayerName = MutableStateFlow<String?>(null)
    val currentPrayerName: StateFlow<String?> = _currentPrayerName.asStateFlow()

    private val _currentMosqueId = MutableStateFlow<String?>(null)
    val currentMosqueId: StateFlow<String?> = _currentMosqueId.asStateFlow()

    fun setPlaying(prayerName: String?, mosqueId: String? = null) {
        _currentPrayerName.value = prayerName
        _currentMosqueId.value = mosqueId
        _isAzanPlaying.value = prayerName != null
    }

    fun clear() {
        _currentPrayerName.value = null
        _currentMosqueId.value = null
        _isAzanPlaying.value = false
    }
}
