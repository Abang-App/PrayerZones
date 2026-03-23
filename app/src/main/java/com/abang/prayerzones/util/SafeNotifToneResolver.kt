package com.abang.prayerzones.util

// Imports you may need
import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.abang.prayerzones.R
import java.io.FileNotFoundException

private const val TAG = "MediaPlayerManager"

// Resolve a best-available notification URI (may return null)
private fun resolveNotificationUri(): Uri? {
    // ✅ FIX: Use getDefaultUri instead of getActualDefaultRingtoneUri to avoid SecurityException
    val actual = try {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    } catch (e: Exception) {
        null
    }
    if (actual != null) return actual

    // Fallback: Settings.System.DEFAULT_NOTIFICATION_URI
    return try {
        Settings.System.DEFAULT_NOTIFICATION_URI
    } catch (e: Exception) {
        null
    }
}

// Play a packaged fallback tone from res/raw (add a small file e.g., res/raw/fallback_tone.ogg)
private fun playFallbackTone(context: Context) {
    try {
        val mp = MediaPlayer.create(context, R.raw.fallback_tone)
        mp?.setOnCompletionListener { it.release() }
        mp?.start()
    } catch (e: Exception) {
        Log.e(TAG, "Fallback tone failed", e)
    }
}

// Safely create and return a Ringtone-like player; returns true if played
fun tryPlayNotificationTone(context: Context): Boolean {
    val uri = resolveNotificationUri()
    Log.d(TAG, "tryPlayNotificationTone: resolved uri=$uri")

    if (uri == null) {
        Log.w(TAG, "No notification URI available; using fallback")
        playFallbackTone(context)
        return true
    }

    return try {
        // Prefer opening via ContentResolver to get an AssetFileDescriptor
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            Log.d(TAG, "Played notification tone from uri=$uri")
            true
        } ?: run {
            Log.w(TAG, "openAssetFileDescriptor returned null for uri=$uri; using fallback")
            playFallbackTone(context)
            true
        }
    } catch (fnf: FileNotFoundException) {
        Log.w(TAG, "Notification file not found: $uri", fnf)
        playFallbackTone(context)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to play notification uri=$uri", e)
        playFallbackTone(context)
        true
    }
}