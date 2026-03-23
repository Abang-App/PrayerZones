package com.abang.prayerzones.viewmodel

import com.abang.prayerzones.model.Mosque

/**
 * A sealed class to represent the distinct states of the UI.
 * This is a top-level class, making it accessible from both the ViewModel and the UI.
 */
sealed class UiState {
    object Loading : UiState()
    data class Success(
        val mosques: List<Mosque>,
        val debugModeEnabled: Boolean = false
    ) : UiState()
    data class Error(val message: String) : UiState()
}
