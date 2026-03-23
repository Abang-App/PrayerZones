package com.abang.prayerzones.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.preference.PreferenceDataStore
import com.abang.prayerzones.datastore.prayerSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Bridge between AndroidX Preferences UI and Jetpack DataStore
 *
 * Maps preference keys from XML to DataStore keys:
 * - pref_slot1_full_azan → slot1_full_azan
 * - pref_secondary_audio_mode → secondary_audio_mode
 * - etc.
 *
 * This ensures that changes in Settings UI immediately persist to DataStore
 * and are visible to the rest of the app.
 *
 * IMPORTANT: The DataStore is defined at the top level to ensure it's a singleton.
 * Creating multiple instances would crash with: "There are multiple DataStores active for the same file"
 */
class PreferencesDataStore(private val context: Context) : PreferenceDataStore() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ══════════════════════════════════════════════════════════════
    // STRING PREFERENCES
    // ══════════════════════════════════════════════════════════════

    override fun putString(key: String, value: String?) {
        scope.launch {
            context.prayerSettingsDataStore.edit { preferences ->
                if (value != null) {
                    preferences[stringPreferencesKey(key)] = value
                } else {
                    preferences.remove(stringPreferencesKey(key))
                }
            }
        }
    }

    override fun getString(key: String, defValue: String?): String? {
        return runBlocking {
            context.prayerSettingsDataStore.data.first()[stringPreferencesKey(key)] ?: defValue
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BOOLEAN PREFERENCES
    // ══════════════════════════════════════════════════════════════

    override fun putBoolean(key: String, value: Boolean) {
        scope.launch {
            context.prayerSettingsDataStore.edit { preferences ->
                preferences[booleanPreferencesKey(key)] = value
            }
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return runBlocking {
            context.prayerSettingsDataStore.data.first()[booleanPreferencesKey(key)] ?: defValue
        }
    }

    // ══════════════════════════════════════════════════════════════
    // INTEGER PREFERENCES (for SeekBars)
    // ══════════════════════════════════════════════════════════════

    override fun putInt(key: String, value: Int) {
        scope.launch {
            context.prayerSettingsDataStore.edit { preferences ->
                preferences[intPreferencesKey(key)] = value
            }
        }
    }

    override fun getInt(key: String, defValue: Int): Int {
        return runBlocking {
            context.prayerSettingsDataStore.data.first()[intPreferencesKey(key)] ?: defValue
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LONG PREFERENCES (not used yet, but good to have)
    // ══════════════════════════════════════════════════════════════

    override fun putLong(key: String, value: Long) {
        scope.launch {
            context.prayerSettingsDataStore.edit { preferences ->
                preferences[intPreferencesKey(key)] = value.toInt()
            }
        }
    }

    override fun getLong(key: String, defValue: Long): Long {
        return runBlocking {
            context.prayerSettingsDataStore.data.first()[intPreferencesKey(key)]?.toLong() ?: defValue
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FLOAT PREFERENCES (not used yet)
    // ══════════════════════════════════════════════════════════════

    override fun putFloat(key: String, value: Float) {
        scope.launch {
            context.prayerSettingsDataStore.edit { preferences ->
                preferences[stringPreferencesKey(key)] = value.toString()
            }
        }
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return runBlocking {
            context.prayerSettingsDataStore.data.first()[stringPreferencesKey(key)]?.toFloat() ?: defValue
        }
    }

    // ══════════════════════════════════════════════════════════════
    // STRING SET PREFERENCES (not used yet)
    // ══════════════════════════════════════════════════════════════

    override fun putStringSet(key: String, values: Set<String>?) {
        // Not implemented - we don't use string sets in this app
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        // Not implemented - we don't use string sets in this app
        return defValues
    }
}
