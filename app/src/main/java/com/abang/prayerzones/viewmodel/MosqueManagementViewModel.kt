package com.abang.prayerzones.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.model.InMosqueMode
import com.abang.prayerzones.model.initialMosques
import com.abang.prayerzones.repository.MosqueRepository
import com.abang.prayerzones.util.AlarmSurgeryManager
import com.abang.prayerzones.util.AlarmScheduler
import com.abang.prayerzones.util.InMosqueModeManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Represents a mosque slot in the management UI
 */
data class MosqueSlot(
    val slotNumber: Int, // 0-3
    val mosque: Mosque?,
    val isNearestSlot: Boolean, // True for slot 0 (GPS-based)
    val isLoading: Boolean = false
)

/**
 * ViewModel for Mosque Management screen
 */
@HiltViewModel
class MosqueManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mosqueRepository: MosqueRepository,
    private val alarmScheduler: AlarmScheduler,
    private val alarmSurgeryManager: AlarmSurgeryManager,
    private val inMosqueModeManager: InMosqueModeManager
) : ViewModel() {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // State for location permission
    private val _locationPermissionGranted = MutableStateFlow(false)
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()

    // State for nearest mosque
    private val _nearestMosque = MutableStateFlow<Mosque?>(null)

    // State for loading slot 0
    private val _slot0Loading = MutableStateFlow(false)

    // State for tracking which slot is being edited (null = no active editing)
    private val _activeSlotIndex = MutableStateFlow<Int?>(null)
    val activeSlotIndex: StateFlow<Int?> = _activeSlotIndex.asStateFlow()

    // Expose selected mosque IDs for duplicate filtering
    val selectedMosqueIds: StateFlow<Set<String>> = mosqueRepository.selectedMosqueIds
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    // Reactive mosque slots from repository
    val mosqueSlots: StateFlow<List<MosqueSlot>> = combine(
        mosqueRepository.selectedMosques,
        _slot0Loading
    ) { selectedMosques, isSlot0Loading ->
        // Build slots list maintaining exact slot positions (with nulls)
        // selectedMosques is List<Mosque?> with exactly 4 entries
        val slots = mutableListOf<MosqueSlot>()

        // Create slot for each position (0-3)
        for (i in 0..3) {
            val mosque = selectedMosques.getOrNull(i)
            slots.add(
                MosqueSlot(
                    slotNumber = i,
                    mosque = mosque,
                    isNearestSlot = (i == 0),
                    isLoading = if (i == 0) isSlot0Loading else false
                )
            )
        }

        slots
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // One-shot UI event: ask user to switch Slot 0
    data class Slot0SwitchProposal(
        val oldMosque: Mosque?,
        val newMosque: Mosque,
        val distanceKmFromOld: Double
    )

    private val _slot0SwitchEvents = MutableSharedFlow<Slot0SwitchProposal>(extraBufferCapacity = 1)
    val slot0SwitchEvents = _slot0SwitchEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private var lastLocation: Location? = null

    // Exposed for distance display in the UI
    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────
    // IN-MOSQUE MODE — UI BRIDGE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called from MosqueListScreen when the user toggles the In-Mosque switch OFF.
     * Delegates to the Hilt-injected singleton so the correct instance handles
     * ringer restoration, timer cancellation, and receiver cleanup.
     */
    fun disableInMosqueMode() {
        inMosqueModeManager.disableAndRestore()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(InMosqueMode.PREF_KEY, InMosqueMode.DISABLED.prefValue)
            .apply()
        Log.d("MosqueManagementVM", "In-Mosque mode disabled via ViewModel bridge")
    }

    private fun kmBetween(a: Location, b: Location): Double {
        return (a.distanceTo(b) / 1000.0)
    }

    private fun computeNearestFor(location: Location): Mosque? {
        return findNearestMosque(location.latitude, location.longitude)
    }

    /**
     * Called when the user accepts the snackbar action to switch Slot 0.
     */
    fun confirmSwitchSlot0(newNearest: Mosque) {
        viewModelScope.launch {
            try {
                _slot0Loading.value = true

                val currentSlots = mosqueRepository.selectedMosques.first()
                val oldSlot0 = currentSlots.getOrNull(0)

                // 1) Cancel alarms for OLD slot 0 mosque only
                oldSlot0?.let { alarmScheduler.cancelAllAlarmsForMosque(it.id) }

                // 2) Replace slot 0 with new mosque (SG rule enforced)
                val saved = mosqueRepository.saveNearestMosqueWithDeDupEnforcingSingaporeRule(newNearest.id)
                if (!saved) {
                    _toastEvents.tryEmit("Singapore mosques share identical timings. Please select a mosque from another region.")
                    return@launch
                }

                // 3) Re-install alarms for active mosques (covers slot 0 today)
                alarmSurgeryManager.installActiveAlarms()

            } finally {
                _slot0Loading.value = false
            }
        }
    }

    init {
        checkLocationPermission()
        // No need to manually load slots - mosqueSlots Flow handles it automatically
    }

    private fun checkLocationPermission() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        _locationPermissionGranted.value = fineLocationGranted || coarseLocationGranted

        if (_locationPermissionGranted.value) {
            fetchNearestMosque()
        }
    }

    fun onLocationPermissionGranted() {
        _locationPermissionGranted.value = true
        fetchNearestMosque()
    }

    /**
     * Fetch the nearest mosque based on GPS location
     */
    fun fetchNearestMosque() {
        if (!_locationPermissionGranted.value) {
            Log.w("MosqueManagement", "Location permission not granted")
            return
        }

        // Check GPS auto-update preference
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val gpsAutoUpdateEnabled = prefs.getBoolean("pref_gps_auto_update", true)

        if (!gpsAutoUpdateEnabled) {
            Log.d("MosqueManagement", "GPS auto-update is disabled by user preference")
            return
        }

        viewModelScope.launch {
            try {
                _slot0Loading.value = true

                val location = getCurrentLocation()
                if (location != null) {
                    val nearest = computeNearestFor(location)
                    _nearestMosque.value = nearest

                    val currentSlots = mosqueRepository.selectedMosques.first()
                    val currentSlot0 = currentSlots.getOrNull(0)

                    // Threshold check: only prompt after >5km movement
                    val movedKm = lastLocation?.let { kmBetween(it, location) } ?: 0.0
                    lastLocation = location
                    _userLocation.value = location

                    if (nearest != null && currentSlot0 != null && nearest.id != currentSlot0.id && movedKm > 5.0) {
                        // Propose switch instead of auto-updating.
                        _slot0SwitchEvents.tryEmit(
                            Slot0SwitchProposal(
                                oldMosque = currentSlot0,
                                newMosque = nearest,
                                distanceKmFromOld = movedKm
                            )
                        )
                        Log.d("MosqueManagement", "Proposed slot0 switch: ${currentSlot0.name} -> ${nearest.name} movedKm=$movedKm")
                    } else if (nearest != null && currentSlot0 == null) {
                        // First-time fill: OK to set immediately.
                        mosqueRepository.saveNearestMosqueWithDeDupEnforcingSingaporeRule(nearest.id)
                        Log.d("MosqueManagement", "Slot0 was empty; filled immediately with: ${nearest.name}")
                    } else {
                        Log.d("MosqueManagement", "No slot0 switch needed. movedKm=$movedKm current=${currentSlot0?.name} nearest=${nearest?.name}")
                    }
                } else {
                    Log.w("MosqueManagement", "Could not get current location")
                }

            } catch (e: Exception) {
                Log.e("MosqueManagement", "Error fetching nearest mosque", e)
            } finally {
                _slot0Loading.value = false
            }
        }
    }

    /**
     * Get current GPS location
     */
    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(): Location? {
        if (!_locationPermissionGranted.value) return null

        return suspendCancellableCoroutine { continuation ->
            try {
                val cancellationTokenSource = CancellationTokenSource()

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).addOnSuccessListener { location ->
                    continuation.resume(location)
                }.addOnFailureListener { exception ->
                    Log.e("MosqueManagement", "Error getting location", exception)
                    continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            } catch (e: Exception) {
                Log.e("MosqueManagement", "Error in getCurrentLocation", e)
                continuation.resume(null)
            }
        }
    }

    /**
     * Find the nearest mosque from the Master List based on GPS coordinates.
     * Uses Location.distanceBetween() for accuracy.
     */
    private fun findNearestMosque(latitude: Double, longitude: Double): Mosque? {
        Log.d("MosqueManagement", "Finding nearest mosque from ($latitude, $longitude)")

        return initialMosques.minByOrNull { mosque ->
            distanceMeters(latitude, longitude, mosque.latitude, mosque.longitude)
        }?.also { nearest ->
            val meters = distanceMeters(latitude, longitude, nearest.latitude, nearest.longitude)
            Log.d("MosqueManagement", "Nearest: ${nearest.name} at ${String.format("%.0f", meters)} m")
        }
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }

    /**
     * Set which slot is currently being edited
     * This activates the search bar for targeted selection
     */
    fun setActiveSlot(slotIndex: Int?) {
        require(slotIndex == null || slotIndex in 0..3) {
            "Slot index must be 0-3 or null"
        }
        _activeSlotIndex.value = slotIndex
        Log.d("MosqueManagement", "Active slot set to: $slotIndex")
    }

    /**
     * Cancel the active slot editing (close search mode)
     */
    fun cancelActiveSlot() {
        _activeSlotIndex.value = null
        Log.d("MosqueManagement", "Active slot cancelled")
    }

    /**
     * Add a mosque to a specific slot (1-3 only)
     * Slot 0 is protected and can only be updated via GPS
     */
    fun addMosqueToSlot(slot: Int, mosque: Mosque) {
        require(slot in 1..3) { "Can only add mosques to slots 1-3" }

        viewModelScope.launch {
            mosqueRepository.saveMosqueToSlot(slot, mosque.id)
            // Clear active slot after successful save
            _activeSlotIndex.value = null
            Log.d("MosqueManagement", "Mosque ${mosque.name} saved to slot $slot")
            // UI updates automatically via Flow - no manual refresh needed
        }
    }

    /**
     * Add a mosque to the currently active slot
     * This is the targeted swap function
     */
    fun addMosqueToActiveSlot(mosque: Mosque) {
        val targetSlot = _activeSlotIndex.value
        require(targetSlot != null) { "No active slot selected" }
        require(targetSlot in 1..3) { "Can only add mosques to slots 1-3" }

        viewModelScope.launch {
            val ok = mosqueRepository.saveMosqueToSlotEnforcingSingaporeRule(targetSlot, mosque.id)
            if (!ok) {
                _toastEvents.tryEmit("Singapore mosques share identical timings. Please select a mosque from another region.")
                Log.w("MosqueManagement", "Blocked SG duplicate for slot=$targetSlot")
                return@launch
            }

            _activeSlotIndex.value = null
            Log.d("MosqueManagement", "Mosque ${mosque.name} saved to active slot $targetSlot")
        }
    }

    /**
     * Remove a mosque from a specific slot (1-3 only)
     * Slot 0 is protected and cannot be manually removed
     *
     * SPECIAL CASE for Slot 0: When cleared, automatically refills with nearest mosque
     */
    fun removeMosqueFromSlot(slot: Int) {
        viewModelScope.launch {
            if (slot == 0) {
                // Special handling: Slot 0 is GPS-managed, just refresh it
                Log.d("MosqueManagement", "Slot 0 removal requested - refreshing with nearest mosque")
                fetchNearestMosque()
            } else {
                require(slot in 1..3) { "Can only remove mosques from slots 1-3" }
                mosqueRepository.removeMosqueFromSlot(slot)
                Log.d("MosqueManagement", "Mosque removed from slot $slot")
                // UI updates automatically via Flow - no manual refresh needed
            }
        }
    }
}
