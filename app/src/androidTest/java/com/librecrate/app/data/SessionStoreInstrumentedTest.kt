package com.librecrate.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreInstrumentedTest {

    @Test
    fun saveAndRetrieveDocumentId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-123")
        assertEquals("doc-123", SessionStore.getLastDocumentId(context))
    }

    @Test
    fun returnsNullWhenNoIdSaved() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.clearLastDocumentId(context)
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun clearRemovesSavedId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-to-clear")
        SessionStore.clearLastDocumentId(context)
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun overwriteReplacesPreviousId() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionStore.saveLastDocumentId(context, "doc-old")
        SessionStore.saveLastDocumentId(context, "doc-new")
        assertEquals("doc-new", SessionStore.getLastDocumentId(context))
    }
}
