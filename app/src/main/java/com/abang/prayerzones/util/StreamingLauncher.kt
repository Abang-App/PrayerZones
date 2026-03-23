package com.abang.prayerzones.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

object StreamingLauncher {
    private const val TAG = "StreamingLauncher"

    fun openStream(context: Context, liveUrl: String) {
        if (liveUrl.isBlank()) return

        val uri = runCatching { Uri.parse(liveUrl) }.getOrNull() ?: return

        // Primary: YouTube app
        if (tryOpenYouTubeApp(context, uri)) return

        // Fallback: browser
        openInBrowser(context, uri)
    }

    private fun tryOpenYouTubeApp(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.youtube")
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "YouTube app not found", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open YouTube app", e)
            false
        }
    }

    private fun openInBrowser(context: Context, uri: Uri) {
        // Prefer Custom Tabs if we can find a provider.
        val provider = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
        if (!provider.isNullOrBlank()) {
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                if (context !is Activity) {
                    customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                customTabsIntent.intent.`package` = provider
                customTabsIntent.launchUrl(context, uri)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Custom Tabs failed, falling back to ACTION_VIEW", e)
            }
        }

        // Last-resort fallback
        val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
            .onFailure { Log.e(TAG, "Failed to open browser fallback", it) }
    }
}
