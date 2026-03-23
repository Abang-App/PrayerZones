package com.abang.prayerzones.ui.components

import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.abang.prayerzones.navigation.NavigationItem
import com.abang.prayerzones.settings.SettingsActivity

@Composable
fun Section4_BottomNav(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val items = listOf(
        NavigationItem.Traveler,
        NavigationItem.Qibla,
        NavigationItem.PrayerZ,
        NavigationItem.Mosque,
        NavigationItem.Settings
    )

    // Track toggle state per item (for version display on 2nd click)
    val toggles = remember { mutableStateMapOf<String, Boolean>() }

    // ✅ Compile-safe app version (no BuildConfig dependency)
    val appVersion = remember {
        runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "?"
        }.getOrDefault("?")
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(modifier = modifier, tonalElevation = 4.dp) {
        items.forEach { item ->
            val isSelected = currentRoute == item.route
            val showVersion = toggles[item.route] ?: false

            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (item.route == NavigationItem.Settings.route) {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    } else {
                        if (isSelected) {
                            // Toggle between name and version on 2nd click
                            toggles[item.route] = !(toggles[item.route] ?: false)
                        } else {
                            toggles.clear()
                            toggles[item.route] = false
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                },
                icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                label = {
                    if (isSelected && showVersion && item.route == NavigationItem.PrayerZ.route) {
                        Text("PzV$appVersion")
                    } else {
                        Text(item.label)
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )
        }
    }
}