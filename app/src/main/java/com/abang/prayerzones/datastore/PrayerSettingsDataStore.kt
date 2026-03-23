package com.abang.prayerzones.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single source of truth for the app's DataStore instance.
 *
 * IMPORTANT: Do NOT define additional preferencesDataStore delegates elsewhere.
 * Multiple instances pointing at the same file can cause:
 * "There are multiple DataStores active for the same file".
 */
val Context.prayerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prayer_settings"
)
