package com.abang.prayerzones.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sealed class representing bottom navigation items
 */
sealed class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    object Traveler : NavigationItem(
        route = "traveler",
        label = "Traveler",
        icon = Icons.Default.AirplanemodeActive
    )

    object Qibla : NavigationItem(
        route = "qibla",
        label = "Qibla",
        icon = Icons.Default.Explore
    )

    object PrayerZ : NavigationItem(
        route = "prayerz",
        label = "PrayerZ",
        icon = Icons.Default.Home
    )

    object Mosque : NavigationItem(
        route = "mosque",
        label = "Mosque",
        icon = Icons.Default.Place
    )

    object Settings : NavigationItem(
        route = "settings",
        label = "Settings",
        icon = Icons.Default.Settings
    )

    companion object {
        val items = listOf(Traveler, Qibla, PrayerZ, Mosque, Settings)
    }
}

