package com.librecrate.app.data.encryption

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptionManagerInstrumentedTest {

    private lateinit var manager: EncryptionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "encryption").deleteRecursively()
        manager = EncryptionManager(context)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "encryption").deleteRecursively()
    }

    @Test
    fun isFirstLaunchReturnsTrueInitially() {
        assertTrue(manager.isFirstLaunch())
        assertFalse(manager.isPasswordSet())
    }

    @Test
    fun initializeWithPasswordCreatesKeys() {
        assertTrue(manager.initializeWithPassword("test_password"))
        assertFalse(manager.isFirstLaunch())
        assertTrue(manager.isPasswordSet())
    }

    @Test
    fun getMasterKeyForSessionReturns32ByteKeyAfterInit() {
        manager.initializeWithPassword("test_password")
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun verifyPasswordCorrectAfterInit() {
        manager.initializeWithPassword("test_password")
        assertTrue(manager.verifyPassword("test_password"))
    }

    @Test
    fun verifyPasswordWrongReturnsFalse() {
        manager.initializeWithPassword("test_password")
        assertFalse(manager.verifyPassword("wrong_password"))
    }

    @Test
    fun verifyPasswordRecoversMasterKey() {
        manager.initializeWithPassword("test_password")
        val originalKey = manager.getMasterKeyForSession()
        manager.lock()
        assertTrue(manager.verifyPassword("test_password"))
        val recoveredKey = manager.getMasterKeyForSession()
        assertNotNull(recoveredKey)
        assertArrayEquals(originalKey, recoveredKey)
    }

    @Test
    fun changePasswordWithCorrectOldPassword() {
        manager.initializeWithPassword("old_password")
        val originalKey = manager.getMasterKeyForSession()
        assertTrue(manager.changePassword("old_password", "new_password"))
        manager.lock()
        assertTrue(manager.verifyPassword("new_password"))
        val recoveredKey = manager.getMasterKeyForSession()
        assertArrayEquals(originalKey, recoveredKey)
    }

    @Test
    fun verifyOldPasswordFailsAfterChange() {
        manager.initializeWithPassword("old_password")
        manager.changePassword("old_password", "new_password")
        assertFalse(manager.verifyPassword("old_password"))
    }

    @Test
    fun disablePasswordClearsKeys() {
        manager.initializeWithPassword("test_password")
        assertTrue(manager.disablePassword())
        assertTrue(manager.isFirstLaunch())
        assertFalse(manager.isPasswordSet())
    }

    @Test
    fun initializeWithPasswordIsIdempotent() {
        assertTrue(manager.initializeWithPassword("test_password"))
        assertTrue(manager.initializeWithPassword("another_password"))
        assertTrue(manager.verifyPassword("another_password"))
    }

    @Test
    fun lockClearsSessionKey() {
        manager.initializeWithPassword("test_password")
        assertNotNull(manager.getMasterKeyForSession())
        manager.lock()
        assertTrue(manager.verifyPassword("test_password"))
        assertNotNull(manager.getMasterKeyForSession())
    }

    @Test
    fun fullPasswordLifecycle() {
        manager.initializeWithPassword("password123")
        val originalKey = manager.getMasterKeyForSession()
        assertNotNull(originalKey)
        assertTrue(manager.verifyPassword("password123"))
        manager.lock()
        assertTrue(manager.verifyPassword("password123"))
        val restoredKey = manager.getMasterKeyForSession()
        assertArrayEquals(originalKey, restoredKey)
    }
}
