package com.librecrate.app.data

import android.content.Context

data class ReaderPreferences(
    val fontSize: Float = 1.3f,
    val fontFamilyName: String = FontFamilyName.SANS_SERIF.name,
    val lineHeight: Float = 1.5f,
    val pageMargins: Float = 1.0f,
    val topBottomMargin: Float = 25f,
)

enum class FontFamilyName(val label: String) {
    SERIF("Serif"),
    SANS_SERIF("Sans Serif"),
    OPEN_DYSLEXIC("OpenDyslexic"),
}

object ReaderPreferencesStore {
    private const val PREFS_NAME = "reader_preferences"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_FONT_FAMILY = "font_family"
    private const val KEY_LINE_HEIGHT = "line_height"
    private const val KEY_PAGE_MARGINS = "page_margins"
    private const val KEY_TOP_BOTTOM_MARGIN = "top_bottom_margin"

    fun load(context: Context): ReaderPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fontSize = prefs.getFloat(KEY_FONT_SIZE, 1.3f)
        val fontFamilyName = prefs.getString(KEY_FONT_FAMILY, FontFamilyName.SANS_SERIF.name)
            ?: FontFamilyName.SANS_SERIF.name
        val lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, 1.5f)
        val pageMargins = prefs.getFloat(KEY_PAGE_MARGINS, 1.0f)
        val topBottomMargin = prefs.getFloat(KEY_TOP_BOTTOM_MARGIN, 25f)
        return ReaderPreferences(
            fontSize = fontSize,
            fontFamilyName = fontFamilyName,
            lineHeight = lineHeight,
            pageMargins = pageMargins,
            topBottomMargin = topBottomMargin,
        )
    }

    fun save(context: Context, preferences: ReaderPreferences) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SIZE, preferences.fontSize)
            .putString(KEY_FONT_FAMILY, preferences.fontFamilyName)
            .putFloat(KEY_LINE_HEIGHT, preferences.lineHeight)
            .putFloat(KEY_PAGE_MARGINS, preferences.pageMargins)
            .putFloat(KEY_TOP_BOTTOM_MARGIN, preferences.topBottomMargin)
            .apply()
    }
}
