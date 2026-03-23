package com.abang.prayerzones.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.os.postDelayed
import com.abang.prayerzones.R

object TonePlayer {

    private const val TAG = "TonePlayer"
    private var currentPlayer: MediaPlayer? = null

    /**
     * Play the user-selected tone from res/raw.
     * @param context Android context
     * @param tonePrefValue String value from preferences (e.g. "tone1", "tone2", "tone3")
     */
    fun playSelectedTone(context: Context, tonePrefValue: String?): Boolean {
        // Stop any currently playing tone before starting a new one
        currentPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Throwable) {}
            currentPlayer = null
        }

        val toneResId = when (tonePrefValue) {
            "tone1" -> R.raw.tone1
            "tone2" -> R.raw.tone2
            "tone3" -> R.raw.tone3
            else -> R.raw.tone1
        }

        return try {
            val afd = context.resources.openRawResourceFd(toneResId)
            val player = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                prepare()
                start()
            }
            currentPlayer = player

            // Auto-stop after 7s max
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (player.isPlaying) player.stop()
                    player.release()
                    Log.d(TAG, "Tone stopped after 7s")
                } catch (_: Throwable) {}
                if (currentPlayer === player) currentPlayer = null
            }, 7000)

            Log.d(TAG, "Playing tone: $tonePrefValue (resId=$toneResId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play tone: $tonePrefValue", e)
            false
        }
    }
}
