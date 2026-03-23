package com.abang.prayerzones.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Manages compass sensors (accelerometer + magnetometer) for Qibla direction.
 * Handles sensor registration, rotation matrix calculation, and device rotation compensation.
 */
@Singleton
class QiblaSensorManager @Inject constructor(
    private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val gravityValues = FloatArray(3)
    private val geomagneticValues = FloatArray(3)
    private var gravitySet = false
    private var geomagneticSet = false

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Azimuth (heading) in degrees: 0° = North, 90° = East, 180° = South, 270° = West
    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()

    // Sensor availability status
    private val _sensorsAvailable = MutableStateFlow(true)
    val sensorsAvailable: StateFlow<Boolean> = _sensorsAvailable.asStateFlow()

    // Low-pass filter alpha for smoothing
    private val ALPHA = 0.25f

    companion object {
        private const val TAG = "QiblaSensorManager"
    }

    init {
        // Check sensor availability
        if (accelerometer == null || magnetometer == null) {
            _sensorsAvailable.value = false
            Log.e(TAG, "Required sensors not available")
        }
    }

    /**
     * Register sensors - call in onResume or onStart
     */
    fun registerSensors() {
        if (!_sensorsAvailable.value) {
            Log.w(TAG, "Cannot register sensors - not available")
            return
        }

        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Accelerometer registered")
        }

        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Magnetometer registered")
        }
    }

    /**
     * Unregister sensors - call in onPause or onStop to save battery
     */
    fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        gravitySet = false
        geomagneticSet = false
        Log.d(TAG, "Sensors unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // Apply low-pass filter for smoothing
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                applyLowPassFilter(event.values, gravityValues)
                gravitySet = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                applyLowPassFilter(event.values, geomagneticValues)
                geomagneticSet = true
            }
        }

        // Calculate azimuth when both sensors have data
        if (gravitySet && geomagneticSet) {
            calculateAzimuth()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> {
                Log.w(TAG, "Sensor accuracy: UNRELIABLE")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> {
                Log.w(TAG, "Sensor accuracy: LOW")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> {
                Log.d(TAG, "Sensor accuracy: MEDIUM")
            }
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> {
                Log.d(TAG, "Sensor accuracy: HIGH")
            }
        }
    }

    /**
     * Low-pass filter for smoothing sensor values
     */
    private fun applyLowPassFilter(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
    }

    /**
     * Calculate azimuth (compass heading) compensating for device rotation
     */
    private fun calculateAzimuth() {
        // Get rotation matrix from gravity and geomagnetic field
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            gravityValues,
            geomagneticValues
        )

        if (!success) {
            Log.w(TAG, "Failed to get rotation matrix")
            return
        }

        // Compensate for device screen rotation (portrait/landscape)
        val adjustedRotationMatrix = FloatArray(9)
        adjustRotationMatrixForScreenRotation(rotationMatrix, adjustedRotationMatrix)

        // Get orientation angles
        SensorManager.getOrientation(adjustedRotationMatrix, orientationAngles)

        // Azimuth is the first value (in radians), convert to degrees
        var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

        // Normalize to 0-360 range
        if (azimuthDegrees < 0) {
            azimuthDegrees += 360f
        }

        // Only update if change is significant (reduce jitter)
        if (abs(azimuthDegrees - _azimuth.value) > 0.5f) {
            _azimuth.value = azimuthDegrees
        }
    }

    /**
     * Adjust rotation matrix based on screen rotation (portrait/landscape)
     * This ensures the compass points correctly regardless of device orientation
     */
    private fun adjustRotationMatrixForScreenRotation(
        inMatrix: FloatArray,
        outMatrix: FloatArray
    ) {
        val displayRotation = windowManager.defaultDisplay.rotation

        // Determine axis remapping based on screen rotation
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_0 -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }

        SensorManager.remapCoordinateSystem(inMatrix, axisX, axisY, outMatrix)
    }
}

