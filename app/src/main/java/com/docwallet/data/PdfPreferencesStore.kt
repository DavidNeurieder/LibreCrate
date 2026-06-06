package com.docwallet.data

import android.content.Context

enum class PageFitMode(val label: String) {
    FIT_WIDTH("Fit Width"),
    FIT_PAGE("Fit Page"),
    ACTUAL_SIZE("Actual Size"),
}

data class PdfPreferences(
    val pageFitMode: PageFitMode = PageFitMode.FIT_WIDTH,
    val nightMode: Boolean = false,
)

object PdfPreferencesStore {
    private const val PREFS_NAME = "pdf_preferences"
    private const val KEY_PAGE_FIT = "page_fit"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun load(context: Context): PdfPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pageFitName = prefs.getString(KEY_PAGE_FIT, PageFitMode.FIT_WIDTH.name)
            ?: PageFitMode.FIT_WIDTH.name
        val pageFitMode = try {
            PageFitMode.valueOf(pageFitName)
        } catch (_: IllegalArgumentException) {
            PageFitMode.FIT_WIDTH
        }
        val nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        return PdfPreferences(pageFitMode = pageFitMode, nightMode = nightMode)
    }

    fun save(context: Context, preferences: PdfPreferences) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAGE_FIT, preferences.pageFitMode.name)
            .putBoolean(KEY_NIGHT_MODE, preferences.nightMode)
            .apply()
    }
}
