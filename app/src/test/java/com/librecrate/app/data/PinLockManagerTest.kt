package com.librecrate.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinLockManagerTest {

    @Before
    fun setUp() {
        PinLockManager.unlock()
    }

    @Test
    fun `isLocked is false initially`() {
        assertFalse(PinLockManager.isLocked)
    }

    @Test
    fun `lock sets isLocked to true`() {
        PinLockManager.lock()
        assertTrue(PinLockManager.isLocked)
    }

    @Test
    fun `unlock sets isLocked to false`() {
        PinLockManager.lock()
        assertTrue(PinLockManager.isLocked)
        PinLockManager.unlock()
        assertFalse(PinLockManager.isLocked)
    }

    @Test
    fun `lock after lock stays true`() {
        PinLockManager.lock()
        PinLockManager.lock()
        assertTrue(PinLockManager.isLocked)
    }

    @Test
    fun `unlock when already unlocked stays false`() {
        PinLockManager.unlock()
        assertFalse(PinLockManager.isLocked)
    }
}
