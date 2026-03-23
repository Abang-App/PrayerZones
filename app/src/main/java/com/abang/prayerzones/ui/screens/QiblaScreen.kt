package com.abang.prayerzones.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abang.prayerzones.viewmodel.QiblaViewModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Qibla Compass screen with refined UI and stability improvements
 */
@Composable
fun QiblaScreen(viewModel: QiblaViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle management for sensors
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startSensors()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopSensors()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopSensors()
        }
    }

    // Collect state
    val rawAzimuth by viewModel.azimuth.collectAsState()
    val qiblaDirection by viewModel.qiblaDirection.collectAsState()
    val cityName by viewModel.cityName.collectAsState()
    val distanceToKaaba by viewModel.distanceToKaaba.collectAsState()
    val locationPermissionGranted by viewModel.locationPermissionGranted.collectAsState()
    val sensorsAvailable by viewModel.sensorsAvailable.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Apply low-pass filter to azimuth for stability (smoothing factor 0.1)
    var filteredAzimuth by remember { mutableFloatStateOf(rawAzimuth) }
    LaunchedEffect(rawAzimuth) {
        val alpha = 0.1f // Low-pass filter smoothing factor
        filteredAzimuth = filteredAzimuth + alpha * (rawAzimuth - filteredAzimuth)
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

    // Request location permission on first launch
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!sensorsAvailable) {
            SensorUnavailableScreen()
        } else if (!locationPermissionGranted) {
            PermissionRequiredScreen(
                onRequestPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )
        } else if (isLoading) {
            QiblaLoadingScreen()
        } else {
            QiblaCompassContent(
                azimuth = filteredAzimuth,
                qiblaDirection = qiblaDirection,
                cityName = cityName,
                distanceToKaaba = distanceToKaaba,
                onRefreshLocation = { viewModel.fetchLocation() }
            )
        }
    }
}

@Composable
private fun QiblaCompassContent(
    azimuth: Float,
    qiblaDirection: Float,
    cityName: String,
    distanceToKaaba: Float,
    onRefreshLocation: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Calculate needle angle (qibla direction relative to device heading)
    val needleAngle = qiblaDirection - azimuth

    // Smooth animation with spring for natural, heavy feel
    val animatedNeedleAngle by animateFloatAsState(
        targetValue = needleAngle,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "needle_rotation"
    )

    // Check if aligned (within 2 degrees)
    val isAligned = abs(needleAngle) <= 2f || abs(needleAngle) >= 358f

    // Track previous alignment state to trigger vibration only once
    var wasAligned by remember { mutableStateOf(false) }
    LaunchedEffect(isAligned) {
        if (isAligned && !wasAligned) {
            // Trigger single vibration when alignment achieved
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
        wasAligned = isAligned
    }

    // Animate background color when aligned
    val backgroundColor by animateColorAsState(
        targetValue = if (isAligned) {
            Color(0xFFE1BEE7) // Light Purple
        } else {
            MaterialTheme.colorScheme.background
        },
        label = "background_color"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: Title and info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 32.dp)
            ) {
                Text(
                    text = "Qibla Direction",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = cityName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (distanceToKaaba > 0) {
                    Text(
                        text = "${String.format("%.0f", distanceToKaaba)} km to Kaaba",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Middle section: Compass
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                RefinedCompassView(
                    azimuth = azimuth,
                    needleAngle = animatedNeedleAngle,
                    qiblaDirection = qiblaDirection
                )
            }

            // Bottom section: Controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                // Refresh button
                OutlinedButton(
                    onClick = onRefreshLocation,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Refresh location",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Location")
                }

                // Current heading display
                Text(
                    text = "Heading: ${String.format("%.0f", azimuth)}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

    }
}


@Composable
private fun RefinedCompassView(
    azimuth: Float,
    needleAngle: Float,
    qiblaDirection: Float
) {
    val purpleColor = Color(0xFF6200EA) // Purple
    val goldColor = Color(0xFFFFD700) // Gold
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val radius = min(canvasWidth, canvasHeight) / 2f
        val center = Offset(canvasWidth / 2f, canvasHeight / 2f)

        // Rotate entire compass based on device azimuth
        rotate(-azimuth, pivot = center) {
            // Draw outer circle
            drawCircle(
                color = surfaceVariantColor,
                radius = radius,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )

            // Draw inner circle (for graduation dots)
            val innerRadius = radius * 0.85f
            drawCircle(
                color = surfaceVariantColor,
                radius = innerRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw 36 graduation dots (every 10 degrees)
            for (i in 0 until 36) {
                val angle = i * 10f
                val angleRad = Math.toRadians(angle.toDouble())
                val dotRadius = 3.dp.toPx()

                val dotX = center.x + innerRadius * sin(angleRad).toFloat()
                val dotY = center.y - innerRadius * cos(angleRad).toFloat()

                drawCircle(
                    color = Color.Black,
                    radius = dotRadius,
                    center = Offset(dotX, dotY)
                )
            }

            // Draw cardinal direction markers
            val cardinalDirections = listOf(
                0f to "N",
                90f to "E",
                180f to "S",
                270f to "W"
            )

            cardinalDirections.forEach { (angle, label) ->
                val angleRad = Math.toRadians(angle.toDouble())
                val markerStart = radius * 0.90f
                val markerEnd = radius * 0.98f

                val startX = center.x + markerStart * sin(angleRad).toFloat()
                val startY = center.y - markerStart * cos(angleRad).toFloat()
                val endX = center.x + markerEnd * sin(angleRad).toFloat()
                val endY = center.y - markerEnd * cos(angleRad).toFloat()

                // Red dash for North only, black for others
                val dashColor = if (angle == 0f) Color.Red else Color.Black
                drawLine(
                    color = dashColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Purple letter pulled inward (away from dots)
                val letterRadius = radius * 0.72f
                val letterX = center.x + letterRadius * sin(angleRad).toFloat()
                val letterY = center.y - letterRadius * cos(angleRad).toFloat()

                val textLayoutResult = textMeasurer.measure(
                    text = label,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = purpleColor
                    )
                )

                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        letterX - textLayoutResult.size.width / 2f,
                        letterY - textLayoutResult.size.height / 2f
                    )
                )
            }

            // Draw Kaaba cube icon on outer circle at qibla direction
            val kaabaAngle = qiblaDirection
            val kaabaAngleRad = Math.toRadians(kaabaAngle.toDouble())
            val kaabaRadius = radius * 0.95f

            val kaabaX = center.x + kaabaRadius * sin(kaabaAngleRad).toFloat()
            val kaabaY = center.y - kaabaRadius * cos(kaabaAngleRad).toFloat()

            // Rotate the Kaaba 90 degrees anti-clockwise so it sits upright on the circle
            rotate(-73f, pivot = Offset(kaabaX, kaabaY)) {
                val cubeSize = 20.dp.toPx()

                // Black cube (square)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(kaabaX - cubeSize / 2f, kaabaY - cubeSize / 2f),
                    size = Size(cubeSize, cubeSize)
                )

                // Golden strip near top
                val stripHeight = 3.dp.toPx()
                drawRect(
                    color = goldColor,
                    topLeft = Offset(
                        kaabaX - cubeSize / 2f,
                        kaabaY - cubeSize / 2f + cubeSize * 0.25f
                    ),
                    size = Size(cubeSize, stripHeight)
                )
            }
        }

        // Draw Diamond Needle (rotates independently)
        rotate(needleAngle, pivot = center) {
            val needleLength = radius * 0.6f
            val needleWidth = 20.dp.toPx()

            // Diamond shape: two triangles back-to-back
            val path = Path()

            // Top triangle (Purple - pointing to Qibla)
            path.moveTo(center.x, center.y - needleLength) // Top point
            path.lineTo(center.x - needleWidth / 2f, center.y) // Left middle
            path.lineTo(center.x + needleWidth / 2f, center.y) // Right middle
            path.close()

            drawPath(
                path = path,
                color = purpleColor
            )

            // Bottom triangle (White)
            val bottomPath = Path()
            bottomPath.moveTo(center.x, center.y + needleLength) // Bottom point
            bottomPath.lineTo(center.x - needleWidth / 2f, center.y) // Left middle
            bottomPath.lineTo(center.x + needleWidth / 2f, center.y) // Right middle
            bottomPath.close()

            drawPath(
                path = bottomPath,
                color = Color.White
            )

            // Outline for visibility
            drawPath(
                path = path,
                color = Color.Black,
                style = Stroke(width = 1.dp.toPx())
            )
            drawPath(
                path = bottomPath,
                color = Color.Black,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Draw center circle
        drawCircle(
            color = purpleColor,
            radius = 12.dp.toPx(),
            center = center
        )

        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = center
        )
    }
}


@Composable
private fun SensorUnavailableScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Sensor unavailable",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Compass Sensors Unavailable",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your device doesn't support compass sensors",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "Location permission",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Location Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We need your location to calculate Qibla direction",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
private fun QiblaLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Getting your location...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

