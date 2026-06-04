package com.docwallet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreTest {

    @Test
    fun `save and retrieve document ID`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        SessionStore.saveLastDocumentId(context, "doc-123")
        assertEquals("doc-123", SessionStore.getLastDocumentId(context))
    }

    @Test
    fun `returns null when no ID saved`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun `clear removes saved ID`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        SessionStore.saveLastDocumentId(context, "doc-456")
        SessionStore.clearLastDocumentId(context)
        assertNull(SessionStore.getLastDocumentId(context))
    }

    @Test
    fun `overwrite replaces previous ID`() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        SessionStore.saveLastDocumentId(context, "doc-old")
        SessionStore.saveLastDocumentId(context, "doc-new")
        assertEquals("doc-new", SessionStore.getLastDocumentId(context))
    }

    @Test
    fun `multiple contexts resolve to same store`() {
        val app1 = RuntimeEnvironment.getApplication()
        val app2 = RuntimeEnvironment.getApplication().applicationContext
        SessionStore.saveLastDocumentId(app1, "shared-doc")
        assertEquals("shared-doc", SessionStore.getLastDocumentId(app2))
    }
}
