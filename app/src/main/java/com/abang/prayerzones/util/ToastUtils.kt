package com.abang.prayerzones.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Utility for showing Toasts safely from any thread.
 */
object ToastUtils {
    fun show(context: Context, message: String) {
        // Ensure toast always runs on the Main (UI) Thread
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}