package com.abang.prayerzones.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.abang.prayerzones.util.AlarmSurgeryManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reschedules all prayer alarms after device reboot.
 *
 * Delegates entirely to AlarmSurgeryManager.installActiveAlarms() — the same hardened
 * path used by midnight-refresh and the ViewModel. This guarantees:
 *  - Each mosque uses its own timeZone (never ZoneId.systemDefault()).
 *  - DataStore slot validation is respected (no ghost alarms for removed mosques).
 *  - Global/secondary notification toggles are honoured.
 *  - Mute states are applied.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var alarmSurgeryManager: AlarmSurgeryManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i("BootReceiver", "Boot/update received — delegating to AlarmSurgeryManager")
            // installActiveAlarms() is coroutine-based internally; safe to call from receiver.
            alarmSurgeryManager.installActiveAlarms()
        }
    }
}

