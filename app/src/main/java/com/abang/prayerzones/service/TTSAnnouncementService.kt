package com.abang.prayerzones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abang.prayerzones.MainActivity
import com.abang.prayerzones.R
import com.abang.prayerzones.util.NotificationQueue
import com.abang.prayerzones.util.TTSAnnouncementEngine

/**
 * Background TTS service for alarm-time announcements.
 *
 * Why: BroadcastReceiver lifecycle can be too short on modern Android (OEM hardening).
 * Running as a foreground service gives the TTS engine time to initialize, pick a voice,
 * and speak reliably.
 */
class TTSAnnouncementService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: TTSAnnouncementEngine? = null

    companion object {
        private const val TAG = "TTSAnnouncementService"
        private const val NOTIFICATION_ID = 9010
        // ✅ v2: Force Samsung to recreate channel. TTS engine speaks directly; channel is silent.
        private const val CHANNEL_ID = "secondary_channel_v2"
        private const val CHANNEL_NAME = "Prayer TTS"

        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_MOSQUE_NAME = "mosque_name"

        private const val WAKE_LOCK_TIMEOUT = 2 * 60 * 1000L // 2 minutes max

        // 🔒 SINGLE-INSTANCE FLAG: Prevent duplicate TTS playback
        @Volatile
        private var isPlaying = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        engine = TTSAnnouncementEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🚫 DEBOUNCE: If already playing, stop this duplicate request
        if (isPlaying) {
            Log.w(TAG, "⚠️ TTS already playing - ignoring duplicate request")
            stopSelf()
            return START_NOT_STICKY
        }

        // Mark as playing
        isPlaying = true

        val prayerName = intent?.getStringExtra(EXTRA_PRAYER_NAME) ?: ""
        val mosqueName = intent?.getStringExtra(EXTRA_MOSQUE_NAME) ?: ""
        val slotIndex = intent?.getIntExtra("slot_index", 1) ?: 1

        // 🚫 SERVICE-LEVEL FAIL-SAFE: Block TTS if Slot 0 is set to tone mode
        // This prevents accidental TTS playback when user wants only tones
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val audioStyle = prefs.getString("pref_main_slot_audio_style", "azan")

        if (slotIndex == 0 && audioStyle == "tone") {
            Log.w("DEBUG_AUDIO", "🚫 TTS Service blocked: Slot 1 is set to Tone mode (audioStyle=$audioStyle)")
            isPlaying = false // Reset flag
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i("Probe", "4. TTS Service Started. Inputs: prayerName=$prayerName, mosqueName=$mosqueName, slotIndex=$slotIndex")

        if (prayerName.isBlank() || mosqueName.isBlank()) {
            Log.e(TAG, "Missing prayerName/mosqueName, stopping")
            isPlaying = false // Reset flag
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Starting background TTS for $prayerName at $mosqueName")

        // Start foreground immediately to avoid background limits
        startForeground(NOTIFICATION_ID, createForegroundNotification(prayerName, mosqueName, slotIndex))

        // Route audio as alarm/notification
        val usage = AudioAttributes.USAGE_ALARM

        engine?.speakAnnouncement(
            prayerName = prayerName,
            mosqueName = mosqueName,
            usage = usage,
            onCompleted = {
                Log.d(TAG, "TTS completed for $prayerName at $mosqueName")
                isPlaying = false // Reset flag when done
                NotificationQueue.onTTSCompleted() // ← allow next queued item to proceed
                stopSelf()
            }
        )

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false // Reset flag when service destroyed
        try {
            engine?.shutdown()
        } catch (_: Throwable) {
        }
        engine = null
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
                description = "Foreground notification used while speaking prayer announcements"
                setSound(null, null)
                enableVibration(false)
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(prayerName: String, mosqueName: String, slotIndex: Int): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("start_destination", com.abang.prayerzones.navigation.NavigationItem.PrayerZ.route)
            putExtra("focus_slot", slotIndex)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // ✅ FIX: Use slotIndex as requestCode so each mosque slot gets a unique PendingIntent.
        // Using requestCode=0 for all slots caused Android to reuse (and overwrite) the same
        // PendingIntent for every mosque, so all notification taps navigated to the same
        // (first-fired) mosque instead of the correct one.
        val contentIntent = PendingIntent.getActivity(
            this,
            slotIndex,
            notificationIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🗣️ $prayerName")
            .setContentText("$mosqueName")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PrayerZones::TTSAnnouncementWakeLock"
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
