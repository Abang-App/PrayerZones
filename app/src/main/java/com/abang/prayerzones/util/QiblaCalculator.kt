package com.abang.prayerzones.util

import android.location.Location
import kotlin.math.*

/**
 * Calculates the Qibla direction (bearing to Kaaba in Mecca) from any location
 */
object QiblaCalculator {

    // Kaaba coordinates in Mecca, Saudi Arabia
    private const val KAABA_LATITUDE = 21.4225
    private const val KAABA_LONGITUDE = 39.8262

    /**
     * Calculate the bearing (angle) to Kaaba from the given location
     * @param latitude User's current latitude
     * @param longitude User's current longitude
     * @return Bearing in degrees (0-360) where 0° = North, 90° = East, etc.
     */
    fun calculateQiblaDirection(latitude: Double, longitude: Double): Float {
        // Method 1: Using Android Location.bearingTo() - Most accurate
        val userLocation = Location("").apply {
            this.latitude = latitude
            this.longitude = longitude
        }

        val kaabaLocation = Location("").apply {
            this.latitude = KAABA_LATITUDE
            this.longitude = KAABA_LONGITUDE
        }

        var bearing = userLocation.bearingTo(kaabaLocation)

        // bearingTo returns -180 to 180, normalize to 0-360
        if (bearing < 0) {
            bearing += 360f
        }

        return bearing
    }

    /**
     * Alternative: Calculate Qibla direction using Great Circle formula
     * This is a backup method that doesn't rely on Android Location class
     */
    fun calculateQiblaDirectionGreatCircle(latitude: Double, longitude: Double): Float {
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val lat2 = Math.toRadians(KAABA_LATITUDE)
        val lon2 = Math.toRadians(KAABA_LONGITUDE)

        val dLon = lon2 - lon1

        // Formula: θ = atan2(sin(Δlong)·cos(lat2), cos(lat1)·sin(lat2) − sin(lat1)·cos(lat2)·cos(Δlong))
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x)).toFloat()

        // Normalize to 0-360
        bearing = (bearing + 360) % 360

        return bearing
    }

    /**
     * Calculate distance to Kaaba in kilometers
     */
    fun calculateDistanceToKaaba(latitude: Double, longitude: Double): Float {
        val userLocation = Location("").apply {
            this.latitude = latitude
            this.longitude = longitude
        }

        val kaabaLocation = Location("").apply {
            this.latitude = KAABA_LATITUDE
            this.longitude = KAABA_LONGITUDE
        }

        // Returns distance in meters
        val distanceMeters = userLocation.distanceTo(kaabaLocation)

        // Convert to kilometers
        return distanceMeters / 1000f
    }

    /**
     * Get Kaaba location for display
     */
    fun getKaabaLocation(): Pair<Double, Double> {
        return Pair(KAABA_LATITUDE, KAABA_LONGITUDE)
    }
}

