package com.abang.prayerzones.ui.screens

import android.Manifest
import android.location.Location
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.draw.alpha
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.initialMosques
import com.abang.prayerzones.ui.components.InMosqueModeDialog
import com.abang.prayerzones.viewmodel.MosqueManagementViewModel
import com.abang.prayerzones.viewmodel.MosqueSlot
import kotlinx.coroutines.flow.collect

/**
 * Mosque Management screen - Manage up to 4 preferred mosques
 * Slot 0: Nearest mosque (GPS-based)
 * Slots 1-3: User-selected mosques
 */
@Composable
fun MosqueListScreen(viewModel: MosqueManagementViewModel = hiltViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Toast collector for SG rule violations
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg: String ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Observe slot-0 switch proposals
    LaunchedEffect(Unit) {
        viewModel.slot0SwitchEvents.collect { proposal ->
            val result = snackbarHostState.showSnackbar(
                message = "New nearest mosque detected: ${proposal.newMosque.name}",
                actionLabel = "Switch",
                withDismissAction = true
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.confirmSwitchSlot0(proposal.newMosque)
            }
        }
    }

    val mosqueSlots by viewModel.mosqueSlots.collectAsState()
    val locationPermissionGranted by viewModel.locationPermissionGranted.collectAsState()
    val selectedMosqueIds by viewModel.selectedMosqueIds.collectAsState()
    val activeSlotIndex by viewModel.activeSlotIndex.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()

    // Search state
    var searchQuery by remember { mutableStateOf("") }

    val hasSingaporeAlready = remember(selectedMosqueIds) {
        selectedMosqueIds.any { it.startsWith("SG", ignoreCase = true) }
    }

    // Filter master list based on search query AND exclude duplicates AND SG rule
    val filteredMosques = remember(searchQuery, selectedMosqueIds, hasSingaporeAlready) {
        fun allow(mosque: Mosque): Boolean {
            if (mosque.id in selectedMosqueIds) return false
            if (hasSingaporeAlready && mosque.id.startsWith("SG", ignoreCase = true)) return false
            return true
        }

        if (searchQuery.isBlank()) {
            // Show all mosques that are NOT already selected
            initialMosques.filter(::allow)
        } else {
            // Search and exclude duplicates
            initialMosques.filter { mosque ->
                (mosque.name.contains(searchQuery, ignoreCase = true) ||
                 mosque.displayCity.contains(searchQuery, ignoreCase = true) ||
                 mosque.displayCountry.contains(searchQuery, ignoreCase = true)) &&
                allow(mosque)
            }
        }
    }

    // Clear search when active slot is cancelled
    LaunchedEffect(activeSlotIndex) {
        if (activeSlotIndex == null) {
            searchQuery = ""
        }
    }

    // Location permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                     permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.onLocationPermissionGranted()
        }
    }

    // Request location permission on first launch if not granted
    LaunchedEffect(locationPermissionGranted) {
        if (!locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Manage Mosques",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Select up to 4 mosques to track prayer times",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Conditional content: Search results when slot is active, otherwise show slots
            if (activeSlotIndex != null) {
                // Search Bar — only visible in search mode
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        ),
                    placeholder = {
                        Text("Search for Slot $activeSlotIndex...")
                    },
                    label = {
                        Text("Editing Slot $activeSlotIndex")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Search Results Mode
                Text(
                    text = "Search Results (${filteredMosques.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredMosques) { mosque ->
                        MosqueSearchResultCard(
                            mosque = mosque,
                            onSelectMosque = {
                                // Use the targeted swap function
                                viewModel.addMosqueToActiveSlot(mosque)
                            }
                        )
                    }

                    if (filteredMosques.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "All available mosques are already selected"
                                } else {
                                    "No mosques found matching \"$searchQuery\""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            } else {
                // Mosque Slots Mode
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(mosqueSlots) { slot ->
                        MosqueSlotCard(
                            slot = slot,
                            userLocation = userLocation,
                            onAddClick = {
                                // Set this slot as active for editing
                                viewModel.setActiveSlot(slot.slotNumber)
                            },
                            onSwapClick = {
                                // Set this slot as active for swapping
                                viewModel.setActiveSlot(slot.slotNumber)
                            },
                            onRemoveClick = {
                                viewModel.removeMosqueFromSlot(slot.slotNumber)
                            },
                            onRefreshClick = {
                                if (slot.isNearestSlot) {
                                    viewModel.fetchNearestMosque()
                                }
                            },
                            onDisableInMosqueMode = {
                                viewModel.disableInMosqueMode()
                            }
                        )
                    }
                }
            }
        }

        // Cancel/Back button when in search mode
        if (activeSlotIndex != null) {
            FloatingActionButton(
                onClick = {
                    viewModel.cancelActiveSlot()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel editing"
                )
            }
        }

        // Snackbar host overlay
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun MosqueSearchResultCard(
    mosque: Mosque,
    onSelectMosque: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectMosque),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val displayName = if (mosque.name.length > 26) {
                    mosque.name.take(26)
                } else {
                    mosque.name
                }

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (mosque.name.length >= 23) 14.sp else 16.sp
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${mosque.displayCity}, ${mosque.displayCountry}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Select mosque",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MosqueSlotCard(
    slot: MosqueSlot,
    userLocation: android.location.Location?,
    onAddClick: () -> Unit,
    onSwapClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onDisableInMosqueMode: () -> Unit = {}
) {
    // In-Mosque Mode dialog state (Nearest/Slot-0 only)
    var showInMosqueDialog by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs   = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // mutableStateOf — reassignable so writes trigger recomposition immediately
    var inMosqueMode by remember {
        mutableStateOf(
            InMosqueMode.fromPref(
                prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
            )
        )
    }
    // Bug #1 fix: observe InMosqueMode.PREF_ACTIVE via SharedPreferences listener so the
    // UI turns Green immediately when InMosqueModeManager writes the flag — no GPS needed.
    var isActivelyInsideMosque by remember {
        mutableStateOf(prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false))
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == InMosqueMode.PREF_ACTIVE) {
                isActivelyInsideMosque = prefs.getBoolean(InMosqueMode.PREF_ACTIVE, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }


    val featureEnabled = inMosqueMode != InMosqueMode.DISABLED
    val accentColor: Color = MaterialTheme.colorScheme.onSurface // Default = off


    // Distance string: "2.3 km" or "450 m"
    val distanceText: String? = remember(userLocation, slot.mosque) {
        if (userLocation != null && slot.mosque != null) {
            val results = FloatArray(1)
            Location.distanceBetween(
                userLocation.latitude, userLocation.longitude,
                slot.mosque.latitude, slot.mosque.longitude,
                results
            )
            val metres = results[0]
            if (metres < 1000f) "${metres.toInt()} m" else "${"%.1f".format(metres / 1000f)} km"
        } else null
    }

    if (showInMosqueDialog && slot.mosque != null) {
        InMosqueModeDialog(
            mosque      = slot.mosque,
            onModeSaved = { newMode ->
                inMosqueMode       = newMode
                showInMosqueDialog = false
            },
            onDismiss   = {
                // Cancelled without saving — revert Switch to what is actually persisted
                inMosqueMode = InMosqueMode.fromPref(
                    prefs.getString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
                )
                showInMosqueDialog = false
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = if (slot.isNearestSlot) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (slot.isNearestSlot)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Left: slot label + mosque info ───────────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    // Slot label row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (slot.isNearestSlot) "📍 Nearest Mosque" else "Slot ${slot.slotNumber}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (slot.isNearestSlot) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        if (slot.isNearestSlot) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "GPS",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mosque info or loading/empty
                    when {
                        slot.isLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Finding nearest...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        slot.mosque != null -> {
                            val displayName = slot.mosque.name.take(26)

                            // Mosque name
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = if (slot.mosque.name.length >= 23) 14.sp else 16.sp
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Location line with distance: "Singapore, Singapore • 2.3 km"
                            val locationLine = buildString {
                                append("${slot.mosque.displayCity}, ${slot.mosque.displayCountry}")
                                if (distanceText != null && slot.isNearestSlot) {
                                    append(" • $distanceText")
                                }
                            }
                            Text(
                                text = locationLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> {
                            Text(
                                text = if (slot.isNearestSlot) "Location permission required" else "Empty slot",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }

                // ── Right: Refresh (Slot 0) or action buttons (Slots 1-3) ────
                if (slot.isNearestSlot && slot.mosque != null) {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh nearest mosque",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (!slot.isNearestSlot) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (slot.mosque != null) {
                            IconButton(onClick = onSwapClick) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "Swap mosque",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = onRemoveClick) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove mosque",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            IconButton(onClick = onAddClick) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add mosque",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Bottom: Auto In-Mosque horizontal toggle (Slot 0 only) ───────
            if (slot.isNearestSlot && slot.mosque != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Label
                    Text(
                        text = "Auto In-Mosque",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Center-Right: Active mode value (only when enabled)
                    if (featureEnabled) {
                        Text(
                            text = inMosqueMode.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    // Right: Switch — green when checked
                    Switch(
                        checked = featureEnabled,
                        onCheckedChange = { isOn ->
                            if (isOn) {
                                showInMosqueDialog = true
                            } else {
                                onDisableInMosqueMode()
                                inMosqueMode = InMosqueMode.DISABLED
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4CAF50),
                            checkedBorderColor = Color(0xFF388E3C)
                        )
                    )
                }
            }
        }
    }
}
