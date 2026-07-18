package com.librecrate.app.data

import android.content.Context

object AppPreferencesStore {
    private const val PREFS_NAME = "app_preferences"
    private const val KEY_SCREENSHOTS_ENABLED = "screenshots_enabled"
    private const val KEY_PIN_ENABLED = "pin_enabled"

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

    fun isPinEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PIN_ENABLED, true)
    }

    fun setPinEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PIN_ENABLED, enabled)
            .apply()
        if (!enabled) {
            PinLockManager.unlock()
        }
    }
}
