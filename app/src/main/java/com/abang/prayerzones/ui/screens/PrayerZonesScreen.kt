package com.abang.prayerzones.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.abang.prayerzones.viewmodel.PrayerViewModel
import com.abang.prayerzones.viewmodel.UiState

@Composable
fun PrayerZonesScreen(
    viewModel: PrayerViewModel = hiltViewModel(),
    focusSlotState: MutableState<Int?>
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is UiState.Loading -> {
            LoadingScreen()
        }
        is UiState.Success -> {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.debugModeEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Red
                    ) {
                        Text(
                            text = "DEBUG MODE ACTIVE - MOCK DATA",
                            modifier = Modifier.padding(vertical = 1.dp, horizontal = 12.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp, // smaller font size
                           textAlign = TextAlign.Center
                        )
                    }
                }

                // The pager will now receive the full list.
                if (state.mosques.isNotEmpty()) {
                    PrayerPager(
                        mosques = state.mosques,
                        parentViewModel = viewModel,
                        focusSlotState = focusSlotState
                    )
                } else {
                    ErrorScreen(message = "Mosque list is empty.")
                }
            }
        }
        is UiState.Error -> {
            ErrorScreen(message = state.message)
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Error: $message")
    }
}
