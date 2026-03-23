package com.abang.prayerzones.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.abang.prayerzones.ui.screens.MosqueListScreen
import com.abang.prayerzones.ui.screens.PrayerZonesScreen
import com.abang.prayerzones.ui.screens.QiblaScreen
import com.abang.prayerzones.ui.screens.SettingsScreen
import com.abang.prayerzones.ui.screens.SupportScreen
import com.abang.prayerzones.ui.screens.TravelerScreen

/**
 * Navigation graph for the app's bottom navigation
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    focusSlotState: MutableState<Int?>
) {
    NavHost(
        navController = navController,
        startDestination = NavigationItem.PrayerZ.route,
        modifier = modifier
    ) {
        composable(NavigationItem.Traveler.route) {
            TravelerScreen(navController = navController)
        }

        composable("support") {
            SupportScreen()
        }

        composable(NavigationItem.Qibla.route) {
            QiblaScreen()
        }

        composable(NavigationItem.PrayerZ.route) {
            // Main screen - bottom bar is at top level now
            PrayerZonesScreen(focusSlotState = focusSlotState)
        }

        composable(NavigationItem.Mosque.route) {
            MosqueListScreen()
        }

        composable(NavigationItem.Settings.route) {
            SettingsScreen(navController)
        }
    }
}
