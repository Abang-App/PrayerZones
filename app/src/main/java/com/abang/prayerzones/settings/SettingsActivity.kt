package com.abang.prayerzones.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.abang.prayerzones.MainActivity
import com.abang.prayerzones.R
import com.abang.prayerzones.util.LanguageManager
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host activity for Settings screens
 *
 * Uses fragment transactions to navigate between:
 * - SettingsRootFragment (main settings hub)
 * - NotificationSettingsFragment (detailed audio settings)
 *
 * Material 3 themed with proper back navigation
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply night mode delegate based on theme preference
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themePreference = prefs.getString("pref_theme", "system") ?: "system"

        when (themePreference) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        android.util.Log.d("SettingsActivity", "Applied night mode: $themePreference")

        // Use default Material 3 theme from app
        setContentView(R.layout.activity_settings)

        // Set up toolbar with back button
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Settings"
        }

        // Load root fragment if this is the first creation
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsRootFragment())
                .commit()
        }

        // Handle system back button with modern API
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    // Finish activity and return to MainActivity
                    finish()
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        // Handle back button in toolbar
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            // Finish activity and return to MainActivity
            finish()
        }
        return true
    }

    /**
     * Handle navigation from root fragment to nested preference screens
     * This is called when user taps preferences with android:fragment attribute
     */
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: androidx.preference.Preference
    ): Boolean {
        // Create the fragment
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment ?: return false
        ).apply {
            arguments = pref.extras
            @Suppress("DEPRECATION")
            setTargetFragment(caller, 0)
        }

        // Navigate to the fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()

        // Update toolbar title
        supportActionBar?.title = pref.title

        return true
    }

    // Inside your Settings or Language Selection logic
    fun onLanguageChanged(newLang: String) {
        LanguageManager.setLanguage(this, newLang)

        // Crucial: Clear the activity stack and restart fresh
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
