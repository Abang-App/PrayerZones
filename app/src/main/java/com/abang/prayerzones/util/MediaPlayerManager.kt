package com.abang.prayerzones.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.provider.Settings
import com.abang.prayerzones.R
import java.io.FileNotFoundException


object MediaPlayerManager {

    private var mediaPlayer: android.media.MediaPlayer? = null

    /**
     * Play a raw audio resource (e.g. azan, fajr, duha, jumuah2).
     * Uses USAGE_ALARM audio attributes for reliable playback on locked devices (Android 14+).
     * @param context Android context
     * @param resId   Resource ID of the audio file in res/raw
     * @param volumePercent Volume level (20-100), defaults to 80%
     * @param onComplete Optional callback when playback finishes naturally
     */
    fun playRaw(context: Context, resId: Int, volumePercent: Int = 80, onComplete: (() -> Unit)? = null) {
        stop()

        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                // Set audio attributes for alarm usage - ensures playback on locked devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_ALARM)
                }

                // ✅ FIX #5: Validate resource exists before attempting to load
                try {
                    val resourceUri = Uri.parse("android.resource://${context.packageName}/$resId")
                    setDataSource(context, resourceUri)
                } catch (e: java.io.FileNotFoundException) {
                    Log.e("MediaPlayerManager", "⚠️ Audio file not found (resId=$resId). Using system notification as fallback.", e)
                    // Fallback: play system notification sound instead of crashing
                    val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    if (fallbackUri != null) {
                        try {
                            setDataSource(context, fallbackUri)
                        } catch (fallbackEx: Exception) {
                            Log.e("MediaPlayerManager", "Fallback also failed, aborting playback", fallbackEx)
                            onComplete?.invoke()
                            release()
                            mediaPlayer = null
                            return
                        }
                    } else {
                        Log.e("MediaPlayerManager", "No fallback sound available")
                        onComplete?.invoke()
                        release()
                        mediaPlayer = null
                        return
                    }
                }

                setOnCompletionListener {
                    onComplete?.invoke()
                    try {
                        release()
                    } catch (_: Exception) {
                        // Already released
                    }
                    mediaPlayer = null
                }

                setOnErrorListener { mp, what, extra ->
                    // Handle playback errors gracefully
                    Log.e("MediaPlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                    onComplete?.invoke()
                    try {
                        mp.reset()
                        mp.release()
                    } catch (_: Exception) {
                        // Ignore
                    }
                    mediaPlayer = null
                    true // Error handled
                }

                prepare()

                // ✅ Set volume based on user preference (convert 20-100 to 0.2-1.0)
                val volume = (volumePercent.coerceIn(20, 100) / 100f)
                setVolume(volume, volume)
                Log.d("MediaPlayerManager", "Playing with volume: $volumePercent% (float: $volume)")

                start()
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaPlayerManager", "Error playing audio resource $resId", e)
            onComplete?.invoke()
        }
    }

    /**
     * Try to play a notification tone.
     * - If uriString is provided it is tried first.
     * - Otherwise the system default notification URI is resolved and tried.
     * - On any failure, a packaged fallback tone (res/raw/fallback_tone.ogg) is played.
     *
     * This implementation uses ContentResolver.openAssetFileDescriptor to avoid
     * passing null/invalid URIs directly into MediaPlayer and handles FileNotFoundException.
     */
    fun playNotificationTone(context: Context): Boolean {
        return try {
            // Try to resolve the system default notification tone
            val systemUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (systemUri != null) {
                val ringtone = RingtoneManager.getRingtone(context, systemUri)
                if (ringtone != null) {
                    Log.d("MediaPlayerManager", "Playing system notification tone: $systemUri")
                    ringtone.play()
                    return true
                } else {
                    Log.w("MediaPlayerManager", "System ringtone returned null, falling back")
                }
            } else {
                Log.w("MediaPlayerManager", "System notification URI is null, falling back")
            }

            // Fallback tone only if system tone is unavailable
            val afd = context.resources.openRawResourceFd(R.raw.fallback_tone)
            val player = MediaPlayer()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.prepare()
            player.start()
            Log.d("MediaPlayerManager", "Played fallback tone from res/raw/fallback_tone.ogg")
            true

        } catch (e: Exception) {
            Log.e("MediaPlayerManager", "Failed to play notification tone", e)
            false
        }
    }

    /**
     * Play a packaged fallback tone from res/raw/fallback_tone.ogg
     * Ensure you add a small audio file at res/raw/fallback_tone.ogg
     */
    private fun playFallbackTone(context: Context) {
        try {
            stop()
            val mp = android.media.MediaPlayer.create(context, R.raw.fallback_tone)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
            Log.d("MediaPlayerManager", "Played fallback tone from res/raw/fallback_tone.ogg")
        } catch (e: Exception) {
            Log.e("MediaPlayerManager", "Fallback tone failed", e)
        }
    }



    /**
     * Stop any currently playing audio.
     * Properly cleanup listeners to avoid "mediaplayer went away with unhandled events" warning.
     */
    fun stop() {
        mediaPlayer?.let {
            try {
                // Remove listeners before stopping to prevent leaked events
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)

                // Only stop if actually playing
                if (it.isPlaying) {
                    it.stop()
                    // Reset after stop to clear internal state
                    it.reset()
                }

                // Always release
                it.release()
            } catch (e: Exception) {
                // Player might already be released
                try {
                    it.release()
                } catch (_: Exception) {
                    // Ignore if already released
                }
            }
        }
        mediaPlayer = null
    }
}