package com.abang.prayerzones.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abang.prayerzones.util.QiblaCalculator
import com.abang.prayerzones.util.QiblaSensorManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for Qibla Compass screen
 * Manages location, sensor data, and Qibla calculations
 */
@HiltViewModel
class QiblaViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qiblaSensorManager: QiblaSensorManager
) : ViewModel() {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Current device azimuth (compass heading)
    val azimuth: StateFlow<Float> = qiblaSensorManager.azimuth

    // Sensor availability
    val sensorsAvailable: StateFlow<Boolean> = qiblaSensorManager.sensorsAvailable

    // Qibla direction (bearing to Kaaba)
    private val _qiblaDirection = MutableStateFlow(0f)
    val qiblaDirection: StateFlow<Float> = _qiblaDirection.asStateFlow()

    // User location
    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    // City name for display
    private val _cityName = MutableStateFlow("Locating...")
    val cityName: StateFlow<String> = _cityName.asStateFlow()

    // Distance to Kaaba in km
    private val _distanceToKaaba = MutableStateFlow(0f)
    val distanceToKaaba: StateFlow<Float> = _distanceToKaaba.asStateFlow()

    // Permission status
    private val _locationPermissionGranted = MutableStateFlow(false)
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object {
        private const val TAG = "QiblaViewModel"
    }

    init {
        checkLocationPermission()
    }

    /**
     * Check if location permission is granted
     */
    private fun checkLocationPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        _locationPermissionGranted.value = hasPermission

        if (hasPermission) {
            fetchLocation()
        } else {
            _isLoading.value = false
        }
    }

    /**
     * Called when location permission is granted by user
     */
    fun onLocationPermissionGranted() {
        _locationPermissionGranted.value = true
        fetchLocation()
    }

    /**
     * Fetch user's current location
     */
    fun fetchLocation() {
        if (!_locationPermissionGranted.value) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        _isLoading.value = true

        viewModelScope.launch {
            try {
                val cancellationToken = CancellationTokenSource()

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).addOnSuccessListener { location ->
                    location?.let {
                        _userLocation.value = it
                        calculateQiblaDirection(it.latitude, it.longitude)
                        fetchCityName(it.latitude, it.longitude)
                        Log.d(TAG, "Location: ${it.latitude}, ${it.longitude}")
                    } ?: run {
                        Log.w(TAG, "Location is null, trying last known location")
                        getLastKnownLocation()
                    }
                    _isLoading.value = false
                }.addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to get location", exception)
                    getLastKnownLocation()
                    _isLoading.value = false
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception getting location", e)
                _isLoading.value = false
            }
        }
    }

    /**
     * Fallback: Get last known location
     */
    private fun getLastKnownLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    _userLocation.value = it
                    calculateQiblaDirection(it.latitude, it.longitude)
                    fetchCityName(it.latitude, it.longitude)
                    Log.d(TAG, "Using last known location: ${it.latitude}, ${it.longitude}")
                } ?: run {
                    _cityName.value = "Location unavailable"
                    Log.w(TAG, "No last known location available")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last location", e)
            _cityName.value = "Location permission required"
        }
    }

    /**
     * Calculate Qibla direction from user's location
     */
    private fun calculateQiblaDirection(latitude: Double, longitude: Double) {
        val bearing = QiblaCalculator.calculateQiblaDirection(latitude, longitude)
        _qiblaDirection.value = bearing

        val distance = QiblaCalculator.calculateDistanceToKaaba(latitude, longitude)
        _distanceToKaaba.value = distance

        Log.d(TAG, "Qibla direction: $bearing°, Distance: $distance km")
    }

    /**
     * Fetch city name using Geocoder
     */
    private fun fetchCityName(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val city = address.locality ?: address.subAdminArea ?: address.adminArea
                    val country = address.countryName

                    _cityName.value = when {
                        city != null && country != null -> "$city, $country"
                        city != null -> city
                        country != null -> country
                        else -> "Unknown location"
                    }

                    Log.d(TAG, "Location: ${_cityName.value}")
                } else {
                    _cityName.value = "Location found"
                    Log.w(TAG, "No address found for coordinates")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching city name", e)
                _cityName.value = "Location found"
            }
        }
    }

    /**
     * Start sensors - call from onResume
     */
    fun startSensors() {
        qiblaSensorManager.registerSensors()
        Log.d(TAG, "Sensors started")
    }

    /**
     * Stop sensors - call from onPause to save battery
     */
    fun stopSensors() {
        qiblaSensorManager.unregisterSensors()
        Log.d(TAG, "Sensors stopped")
    }
}

