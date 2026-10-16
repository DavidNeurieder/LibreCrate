package com.librecrate.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRoutingTest {

    @Test
    fun `first launch goes to password setup`() {
        assertEquals(
            Routes.PASSWORD_SETUP,
            SessionRouting.startDestination(isPasswordSet = false, hasSessionKey = false),
        )
    }

    @Test
    fun `password set with a live session key goes to the library`() {
        assertEquals(
            Routes.LIBRARY,
            SessionRouting.startDestination(isPasswordSet = true, hasSessionKey = true),
        )
    }

    @Test
    fun `password set without a session key asks for the password`() {
        assertEquals(
            Routes.UNLOCK,
            SessionRouting.startDestination(isPasswordSet = true, hasSessionKey = false),
        )
    }

    @Test
    fun `no password and no session key is still first launch`() {
        // Guards against the old fall-through branch that silently opened the
        // library while the vault was closed ("no documents" instead of a prompt).
        assertEquals(
            Routes.PASSWORD_SETUP,
            SessionRouting.startDestination(isPasswordSet = false, hasSessionKey = true),
        )
    }

    @Test
    fun `a locked session with a password must prompt again`() {
        assertTrue(SessionRouting.shouldPromptForPassword(isPasswordSet = true, sessionLocked = true))
    }

    @Test
    fun `an unlocked session must not prompt`() {
        assertFalse(SessionRouting.shouldPromptForPassword(isPasswordSet = true, sessionLocked = false))
    }

    @Test
    fun `no password means nothing to prompt for`() {
        assertFalse(SessionRouting.shouldPromptForPassword(isPasswordSet = false, sessionLocked = true))
    }
}
