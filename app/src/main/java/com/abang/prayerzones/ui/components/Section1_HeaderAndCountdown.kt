// Section1_HeaderAndCountdown.kt
package com.abang.prayerzones.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.abang.prayerzones.model.Mosque
import com.abang.prayerzones.viewmodel.MosqueDetailViewModel
import kotlin.math.max
import kotlin.math.min

@Composable
fun Section1_HeaderAndCountdown(
    mosque: Mosque,
    viewModel: MosqueDetailViewModel,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified   // Default/Orange/Green from In-Mosque Mode state
) {
    val countdowns by viewModel.countdownStrings.collectAsState()
    val nextPrayerInfo by viewModel.nextPrayerInfo.collectAsState()
    val dateText by viewModel.dateString.collectAsState()

    // Extract values from state for display
    val nextPrayerName = nextPrayerInfo?.name ?: "..."
    val countdown = countdowns.first
    val localTime = countdowns.second

    // Dynamic font sizing for mosque name (22-32sp range)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val dynamicFontSize = remember(mosque.name, density) {
        val maxWidthPx = with(density) { (350.dp).toPx() } // Approx screen width minus padding
        val maxFontSize = 32.sp
        val minFontSize = 20.sp

        // Start with max font and scale down if needed
        var fontSize = maxFontSize
        val style = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )

        val textLayoutResult = textMeasurer.measure(
            text = mosque.name,
            style = style,
            constraints = Constraints(maxWidth = Int.MAX_VALUE)
        )

        // If text is too wide, calculate appropriate font size
        if (textLayoutResult.size.width > maxWidthPx) {
            val ratio = maxWidthPx / textLayoutResult.size.width
            fontSize = (fontSize.value * ratio).sp
            // Clamp between min and max
            fontSize = max(minFontSize.value, min(maxFontSize.value, fontSize.value)).sp
        }

        fontSize
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Mosque Name with dynamic font scaling (20-28sp)
        Text(
            text = mosque.name,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = dynamicFontSize
            ),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = titleColor,             // ← uses param; Color.Unspecified = theme default
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Countdown Block with staircase alignment
        // Luminance-based color: reads the actual surface the text sits on.
        // If background is dark (luminance < 0.5) → light purple; otherwise → deep purple.
        // This is reliable regardless of isSystemInDarkTheme() behaviour on specific OEMs.
        val backgroundColor = MaterialTheme.colorScheme.surface
        val ltColor = if (backgroundColor.luminance() < 0.5f) Color(0xFFCE93D8) else Color(0xFF7B1FA2)

        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "$nextPrayerName in:",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = countdown,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = localTime, // e.g., "LT 19:30"
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = ltColor,
                modifier = Modifier.align(Alignment.End)
            )
        }

       // Spacer(modifier = Modifier.height(4.dp))

        // Date Display // e.g., "Sat 18 Oct | 26 Rabi-2 1447"
        Text(
            text = dateText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
