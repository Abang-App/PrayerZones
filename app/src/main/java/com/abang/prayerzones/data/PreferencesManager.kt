package com.abang.prayerzones.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Singleton DataStore instance to prevent "multiple DataStores active for the same file" error.
 *
 * This top-level property ensures only ONE DataStore instance is created for the entire app.
 * All components (Repository, Receiver, ViewModel) must use this shared instance.
 */
val Context.mosquePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mosque_preferences"
)
