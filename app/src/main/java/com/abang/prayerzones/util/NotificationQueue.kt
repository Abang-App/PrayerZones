package com.abang.prayerzones.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abang.prayerzones.MainActivity
import com.abang.prayerzones.R

/**
 * Serialises concurrent secondary-slot prayer notifications (tone and/or TTS).
 *
 * Problem this solves:
 * Two distant mosques can fire at the exact same UTC instant (e.g. Al-Khair Maghrib
 * and Al-Haram Asr both resolve to 19:30 device local time). Without queuing, both
 * TonePlayer instances and both TTSAnnouncementService starts collide — tones overlap
 * and TTS is silently dropped by the isPlaying guard in TTSAnnouncementService.
 *
 * Strategy 1 — Azan is playing:
 *   Android's AudioFocus AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK natively reduces the Azan
 *   volume when a USAGE_ALARM / USAGE_NOTIFICATION_EVENT source requests focus, then
 *   restores it. No additional code needed for ducking — the OS handles it.
 *   The short tone / TTS is simply enqueued and runs immediately via this queue.
 *
 * Strategy 2 — No Azan playing, two short notifications collide:
 *   Events are queued. Each item runs only after:
 *     a) the previous item signals completion, AND
 *     b) POST_COMPLETION_GAP_MS (10 s) has elapsed — so the user can distinguish
 *        both events as separate alerts.
 *
 * Thread safety: all state mutations are confined to the main thread via Handler.
 */
object NotificationQueue {

    private const val TAG = "NotificationQueue"

    /** Gap inserted after each item completes before the next one starts. */
    private const val POST_COMPLETION_GAP_MS = 10_000L

    /** Maximum time we wait for an item to self-complete before we force-advance. */
    private const val ITEM_TIMEOUT_MS = 35_000L // tone ≤7 s + TTS ≤20 s + margin

    /** Channel used for tone-only heads-up cards (no foreground service involved). */
    private const val TONE_NOTIF_CHANNEL_ID = "tone_only_channel_v1"
    private const val TONE_NOTIF_CHANNEL_NAME = "Prayer Tone Notifications"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Sealed type representing one queued notification event. */
    sealed class QueueItem {
        /**
         * Play a short tone then optionally speak TTS.
         * @param tonePrefValue Preference value forwarded to TonePlayer (e.g. "tone1").
         *                      Null = skip tone, go straight to TTS if present.
         * @param ttsIntent     Ready-to-send Intent for TTSAnnouncementService, or null.
         * @param context       Application context.
         * @param isTTSEnabled  Whether TTS should follow the tone.
         * @param prayerName    Prayer name for the heads-up notification card.
         * @param mosqueName    Mosque name for the heads-up notification card.
         * @param slotIndex     Pager slot index so the tap navigates to the right mosque.
         */
        data class SecondaryNotification(
            val context: Context,
            val tonePrefValue: String?,
            val ttsIntent: Intent?,
            val isTTSEnabled: Boolean,
            val label: String,          // For logging: "mosque/prayer"
            val prayerName: String = "",
            val mosqueName: String = "",
            val slotIndex: Int = 1
        ) : QueueItem()
    }

    // ── State (main-thread only) ─────────────────────────────────────────────

    private val queue = ArrayDeque<QueueItem>()
    @Volatile private var isProcessing = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a secondary notification. If the queue is idle it starts immediately;
     * otherwise it waits for the current item + POST_COMPLETION_GAP_MS.
     *
     * Safe to call from any thread — enqueue is posted to the main handler.
     */
    fun enqueue(item: QueueItem) {
        mainHandler.post {
            queue.addLast(item)
            Log.d(TAG, "Enqueued: ${labelOf(item)} | queue size=${queue.size} | processing=$isProcessing")
            if (!isProcessing) processNext()
        }
    }

    // ── Internal processing ───────────────────────────────────────────────────

    private fun processNext() {
        val item = queue.removeFirstOrNull() ?: run {
            isProcessing = false
            Log.d(TAG, "Queue empty — idle")
            return
        }

        isProcessing = true
        Log.d(TAG, "Processing: ${labelOf(item)}")

        when (item) {
            is QueueItem.SecondaryNotification -> processSecondaryNotification(item)
        }
    }

    private fun processSecondaryNotification(item: QueueItem.SecondaryNotification) {
        val context = item.context

        // ── Step 0: Post heads-up card for tone-only mode ────────────────────
        // When TTS is disabled, TTSAnnouncementService never starts and therefore
        // no foreground notification card appears. We post a standalone heads-up
        // notification here so the user sees the card identical to TTS/Tone+TTS modes.
        if (!item.isTTSEnabled && (item.prayerName.isNotBlank() || item.mosqueName.isNotBlank())) {
            postToneOnlyHeadsUp(context, item.prayerName, item.mosqueName, item.slotIndex)
        }

        // ── Step 1: Play tone (if configured) ───────────────────────────────
        val toneCompleteMs: Long
        if (item.tonePrefValue != null) {
            Log.d(TAG, "Playing tone='${item.tonePrefValue}' for ${item.label}")
            TonePlayer.playSelectedTone(context, item.tonePrefValue)
            // Gap1: 1 s between tone start and TTS start (they're for the same event).
            toneCompleteMs = 1_000L
        } else {
            toneCompleteMs = 0L
        }

        // ── Step 2: Launch TTS after tone finishes (if enabled) ─────────────
        if (item.isTTSEnabled && item.ttsIntent != null) {
            mainHandler.postDelayed({
                Log.d(TAG, "Launching TTS service for ${item.label}")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(item.ttsIntent)
                    } else {
                        context.startService(item.ttsIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start TTS for ${item.label}", e)
                }
            }, toneCompleteMs)
        }

        // ── Step 3: Advance queue after item completes + Gap2 ───────────────
        val advanceDelay = toneCompleteMs + POST_COMPLETION_GAP_MS +
                if (item.isTTSEnabled) 20_000L else 0L

        mainHandler.postDelayed({
            Log.d(TAG, "Item complete (timeout guard): ${item.label} — advancing queue")
            advanceQueue()
        }, advanceDelay.coerceAtMost(ITEM_TIMEOUT_MS + POST_COMPLETION_GAP_MS))
    }

    /**
     * Called by TTSAnnouncementService when TTS actually finishes speaking.
     * This allows exact-time advancement instead of waiting for the timeout.
     */
    fun onTTSCompleted() {
        mainHandler.postDelayed({
            Log.d(TAG, "TTS completed signal received — advancing queue after gap")
            advanceQueue()
        }, POST_COMPLETION_GAP_MS)
    }

    private fun advanceQueue() {
        mainHandler.post { processNext() }
    }

    /**
     * Posts a heads-up notification card for tone-only mode.
     * Normally TTSAnnouncementService provides the card as its foreground notification.
     * When TTS is disabled the service is never started, so we post this standalone card.
     * It auto-cancels after 10 seconds (tone duration + small margin).
     */
    private fun postToneOnlyHeadsUp(
        context: Context,
        prayerName: String,
        mosqueName: String,
        slotIndex: Int
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    TONE_NOTIF_CHANNEL_ID,
                    TONE_NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Heads-up card shown during short tone prayer notifications"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("start_destination", "prayerz")
                putExtra("focus_slot", slotIndex)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT

            val notifId = 8000 + slotIndex
            val pi = PendingIntent.getActivity(context, 200 + slotIndex, tapIntent, piFlags)

            val notif = NotificationCompat.Builder(context, TONE_NOTIF_CHANNEL_ID)
                .setContentTitle("🔔 $prayerName")
                .setContentText(mosqueName)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000L)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            nm.notify(notifId, notif)
            Log.d(TAG, "Posted tone-only heads-up for $prayerName @ $mosqueName (slot=$slotIndex)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post tone-only heads-up", e)
        }
    }

    private fun labelOf(item: QueueItem) = when (item) {
        is QueueItem.SecondaryNotification -> item.label
    }
}

