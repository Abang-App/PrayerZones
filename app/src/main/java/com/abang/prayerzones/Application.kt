package com.abang.prayerzones

import android.app.Application
import com.abang.prayerzones.util.AlarmCleanupHelper
import com.abang.prayerzones.util.NotificationHelper
import com.abang.prayerzones.util.PermissionPreferences
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PrayerZonesApp : Application() {
    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var permissionPreferences: PermissionPreferences

    @Inject
    lateinit var alarmCleanupHelper: AlarmCleanupHelper

    override fun onCreate() {
        super.onCreate()
        // Hilt will inject dependencies after super.onCreate()
        notificationHelper.createPrayerNotificationChannel()
        notificationHelper.createAdhanNotificationChannel()

        // Reset permission check flag on app startup (new session)
        permissionPreferences.resetSession()

        FirebaseApp.initializeApp(this)
    }
}