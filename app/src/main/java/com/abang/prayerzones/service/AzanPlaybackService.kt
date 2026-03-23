package com.abang.prayerzones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abang.prayerzones.MainActivity
import com.abang.prayerzones.R
import com.abang.prayerzones.util.MediaPlayerManager
import com.abang.prayerzones.util.PlaybackState

class AzanPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "AzanPlaybackService"
        private const val NOTIFICATION_ID = 9001
        // ✅ v3: Force Samsung to recreate the channel. Audio is played via MediaPlayerManager,
        // NOT via channel sound, so the channel itself remains silent (no double-playback).
        // Incrementing the ID invalidates any cached channel config on the device.
        private const val CHANNEL_ID = "azan_channel_v3"
        private const val CHANNEL_NAME = "Azan Playback"

        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_SOUND_RES_ID = "sound_res_id"
        const val EXTRA_PRAYER_TIME = "prayer_time"
        const val EXTRA_MOSQUE_ID = "mosque_id"
        const val EXTRA_MOSQUE_NAME = "mosque_name"

        private const val WAKE_LOCK_TIMEOUT = 5 * 60 * 1000L // 5 minutes max
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Handle the Stop Action from the notification button
        if (intent?.action == "STOP_AZAN") {
            Log.d(TAG, "Stop action received via notification")
            runCatching { MediaPlayerManager.stop() }
            PlaybackState.clear()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // 🚫 MANDATORY GATEKEEPER: Prevent phantom Azan playback
        // Extract prayer name first to check for Duha exception
        val prayerName = intent?.getStringExtra(EXTRA_PRAYER_NAME) ?: "Prayer Time"
        val isDuha = prayerName.equals("Duha", ignoreCase = true)

        // Read user's audio style preference
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val audioStyle = prefs.getString("pref_main_slot_audio_style", "azan")

        // BLOCK phantom playback UNLESS:
        // - audioStyle is "azan" OR
        // - This is Duha (always allowed to play via service)
        if (audioStyle != "azan" && !isDuha) {
            Log.e("DEBUG_AUDIO", "🚫 AzanPlaybackService BLOCKED. User selected: [$audioStyle], Prayer: $prayerName")

            // Must show notification to prevent RemoteServiceException crash
            val soundResId = intent?.getIntExtra(EXTRA_SOUND_RES_ID, 0) ?: 0
            val prayerTime = intent?.getStringExtra(EXTRA_PRAYER_TIME) ?: ""
            val mosqueName = intent?.getStringExtra(EXTRA_MOSQUE_NAME) ?: ""
            val silentNotification = createForegroundNotification(prayerName, prayerTime, mosqueName, intent)
            startForeground(NOTIFICATION_ID, silentNotification)

            // Immediately stop without playing audio
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d("DEBUG_AUDIO", "✅ AzanPlaybackService ALLOWED. Style: [$audioStyle], Prayer: $prayerName, IsDuha: $isDuha")

        val soundResId = intent?.getIntExtra(EXTRA_SOUND_RES_ID, 0) ?: 0
        val prayerTime = intent?.getStringExtra(EXTRA_PRAYER_TIME) ?: ""
        val mosqueId = intent?.getStringExtra(EXTRA_MOSQUE_ID)
        val mosqueName = intent?.getStringExtra(EXTRA_MOSQUE_NAME) ?: ""

        PlaybackState.setPlaying(prayerName, mosqueId)

        Log.d(TAG, "Starting azan playback for $prayerName")

        // Start foreground immediately to prevent being killed
        val notification = createForegroundNotification(prayerName, prayerTime, mosqueName, intent)
        startForeground(NOTIFICATION_ID, notification)

        // Play azan with completion callback
        if (soundResId != 0) {
            try {
                // ✅ Read volume preference for Slot 1 (20-100%)
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                val volumePercent = prefs.getInt("pref_slot1_volume", 80)

                Log.d(TAG, "Playing azan with volume: $volumePercent%")

                MediaPlayerManager.playRaw(this, soundResId, volumePercent) {
                    Log.d(TAG, "Azan playback completed for $prayerName")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing azan", e)
                stopSelf()
            }
        } else {
            Log.e(TAG, "Invalid sound resource ID")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        MediaPlayerManager.stop()
        PlaybackState.clear()
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        releaseWakeLock()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification shown during azan playback"
                // ✅ Do NOT call setSound(null,null) — that silences the channel and
                // causes Android to demote effective importance, blocking heads-up display.
                // Audio is handled by MediaPlayerManager, not this notification channel.
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(prayerName: String, prayerTime: String, mosqueName: String, intent: Intent?): Notification {
        // ✅ FIX: Use slotIndex as requestCode so each mosque slot gets a unique PendingIntent.
        // Using requestCode=0 for all slots caused Android to reuse (and overwrite) the same
        // PendingIntent for every mosque, so all notification taps navigated to the same
        // (first-fired) mosque instead of the correct one.
        val slotIndex = intent?.getIntExtra("slot_index", 0) ?: 0
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            // ✅ FIX: Use SINGLE_TOP instead of CLEAR_TOP to avoid activity recreation
            // CLEAR_TOP was causing the Compose UI to briefly reset, making the card miss its window
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("start_destination", com.abang.prayerzones.navigation.NavigationItem.PrayerZ.route)
            // Forward the slot index so the app pager scrolls to the right mosque.
            putExtra("focus_slot", slotIndex)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Use slotIndex as requestCode for content intent (unique per mosque slot)
        val pendingIntent = PendingIntent.getActivity(
            this,
            slotIndex,
            notificationIntent,
            pendingIntentFlags
        )

        // ✅ FIX: fullScreenIntent makes the notification pop up as a heads-up overlay
        // Offset by 100 to avoid collision with content intent requestCodes (0-3)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            100 + slotIndex,
            notificationIntent,
            pendingIntentFlags
        )

        // Stop action intent
        val stopIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = "STOP_AZAN"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🕌 $prayerName - $prayerTime")
            .setContentText("Azan at ${if (mosqueName.isBlank()) "mosque" else mosqueName} is playing... Tap to open app.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            // ✅ Do NOT call setSilent(true) — it suppresses heads-up at the notification level.
            // The notification channel has no sound; MediaPlayerManager handles Azan audio separately.
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_stop,
                "STOP AZAN",
                stopPendingIntent
            )
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PrayerZones::AzanPlaybackWakeLock"
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT)
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }
}
