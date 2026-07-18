package com.librecrate.app.data

import android.content.Context

object SessionStore {
    private const val PREFS_NAME = "session"
    private const val KEY_LAST_DOCUMENT_ID = "last_document_id"

    fun saveLastDocumentId(context: Context, documentId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DOCUMENT_ID, documentId)
            .apply()
    }

    fun getLastDocumentId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DOCUMENT_ID, null)
    }

    fun clearLastDocumentId(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_DOCUMENT_ID)
            .apply()
    }
}
