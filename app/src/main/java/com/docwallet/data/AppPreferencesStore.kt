package com.docwallet.data

import android.content.Context

object AppPreferencesStore {
    private const val PREFS_NAME = "app_preferences"
    private const val KEY_SCREENSHOTS_ENABLED = "screenshots_enabled"

    fun isScreenshotsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREENSHOTS_ENABLED, false)
    }

    fun setScreenshotsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREENSHOTS_ENABLED, enabled)
            .apply()
    }
}
