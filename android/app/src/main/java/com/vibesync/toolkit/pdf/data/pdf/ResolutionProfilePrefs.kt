package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.content.SharedPreferences

/** Dedicated persistence boundary so a future DataStore migration is localized to one file. */
class ResolutionProfilePrefs(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE
    )

    fun read(): ResolutionProfile {
        val stored = preferences.getString(KEY_RESOLUTION_PROFILE, null) ?: return DEFAULT_PROFILE
        return runCatching { ResolutionProfile.valueOf(stored) }.getOrDefault(DEFAULT_PROFILE)
    }

    fun write(profile: ResolutionProfile) {
        preferences.edit().putString(KEY_RESOLUTION_PROFILE, profile.name).apply()
    }

    companion object {
        const val PREFERENCES_FILE = "scan_settings_prefs"
        const val KEY_RESOLUTION_PROFILE = "resolution_profile"
        val DEFAULT_PROFILE = ResolutionProfile.STANDARD
    }
}
