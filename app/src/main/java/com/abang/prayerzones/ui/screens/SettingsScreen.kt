package com.abang.prayerzones.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.abang.prayerzones.navigation.NavigationItem

/**
 * Settings screen - navigates back to home immediately
 *
 * This screen is only hit when returning from SettingsActivity.
 * It immediately navigates back to the home screen so the user
 * sees the prayer list instead of a blank placeholder.
 */
@Composable
fun SettingsScreen(navController: NavController) {
    // Immediately navigate back to home when this screen is shown
    LaunchedEffect(Unit) {
        navController.navigate(NavigationItem.PrayerZ.route) {
            popUpTo(navController.graph.startDestinationId) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }

    // Show loading indicator briefly while navigating
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
