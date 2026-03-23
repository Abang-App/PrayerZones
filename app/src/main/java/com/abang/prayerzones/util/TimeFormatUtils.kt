// File: com.abang.prayerzones.util.TimeFormatUtils.kt
package com.abang.prayerzones.util

fun formatCountdown(secondsUntil: Long): String {
    if (secondsUntil <= 0) return "0s"
    val hrs = secondsUntil / 3600
    val mins = (secondsUntil % 3600) / 60
    val secs = secondsUntil % 60

    return when {
        hrs >= 1 -> "${hrs}h ${mins}m"
        mins >= 10 -> "${mins}m"
        mins >= 1 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}