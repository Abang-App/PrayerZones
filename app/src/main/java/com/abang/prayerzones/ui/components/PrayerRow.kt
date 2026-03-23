package com.abang.prayerzones.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abang.prayerzones.util.formatCountdown

@Composable
fun PrayerRow(
    prayerKey: String,
    timeStr: String,
    isNext: Boolean,
    secondsRemaining: Long?,
    onSelect: (String) -> Unit
) {
    val countdownText = remember(secondsRemaining) {
        secondsRemaining?.let { formatCountdown(it) } ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(prayerKey) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = prayerKey,
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = timeStr,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (isNext && countdownText.isNotBlank()) {
            Text(
                text = countdownText,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic
            )
        }
    }
}