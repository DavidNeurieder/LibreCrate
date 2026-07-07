package com.docwallet.data.encryption

import android.app.Application
import com.docwallet.vault.crypto.Argon2Hasher
import com.docwallet.vault.crypto.KeyStoreCryptographer
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EncryptionManagerTest {

    private lateinit var manager: EncryptionManager
    private lateinit var mockHasher: Argon2Hasher

    @Before
    fun setUp() {
        mockHasher = mockk()
        every { mockHasher.hash(
            password = any(),
            salt = any(),
            tCostInIterations = any(),
            mCostInKibibyte = any(),
            parallelism = any(),
            hashLengthInBytes = any(),
        ) } answers {
            val password = arg<ByteArray>(0)
            val salt = arg<ByteArray>(1)
            val hashLen = arg<Int>(5)
            ByteArray(hashLen) { i ->
                (password.getOrElse(i % password.size) { 0 } +
                 salt.getOrElse(i % salt.size) { 0 } + i).toByte()
            }
        }

        val context = RuntimeEnvironment.getApplication().applicationContext
        val testCryptographer = TestKeyStoreCryptographer()
        manager = EncryptionManager(context, mockHasher, testCryptographer)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isFirstLaunch returns true initially`() {
        assertTrue(manager.isFirstLaunch())
    }

    @Test
    fun `initializeDeviceKeyMode creates wrapped key and device key files`() {
        manager.initializeDeviceKeyMode()
        assertFalse(manager.isFirstLaunch())
        assertFalse(manager.isPasswordSet())
    }

    @Test
    fun `isFirstLaunch returns false after init`() {
        manager.initializeDeviceKeyMode()
        assertFalse(manager.isFirstLaunch())
    }

    @Test
    fun `isPasswordSet returns false in device key mode`() {
        manager.initializeDeviceKeyMode()
        assertFalse(manager.isPasswordSet())
    }

    @Test
    fun `getMasterKeyForSession returns non-null key in device key mode`() {
        manager.initializeDeviceKeyMode()
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `getMasterKeyForSession returns key after lock when device key present`() {
        manager.initializeDeviceKeyMode()
        val key1 = manager.getMasterKeyForSession()
        manager.lock()
        val key2 = manager.getMasterKeyForSession()
        assertNotNull(key1)
        assertNotNull(key2)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun `initializeWithPassword creates wrapped key and device key files`() {
        assertTrue(manager.initializeWithPassword("test_password"))
        assertFalse(manager.isFirstLaunch())
        assertTrue(manager.isPasswordSet())
    }

    @Test
    fun `initializeWithPassword sets up master key for session`() {
        manager.initializeWithPassword("test_password")
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `verifyPassword with correct password after init`() {
        manager.initializeWithPassword("test_password")
        assertTrue(manager.verifyPassword("test_password"))
    }

    @Test
    fun `verifyPassword with wrong password after init returns false`() {
        manager.initializeWithPassword("test_password")
        assertFalse(manager.verifyPassword("wrong_password"))
    }

    @Test
    fun `getMasterKeyForSession returns key via device key after verify`() {
        manager.initializeWithPassword("test_password")
        manager.lock()
        manager.verifyPassword("test_password")
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `getMasterKeyForSession returns key via device key after lock`() {
        manager.initializeWithPassword("test_password")
        manager.lock()
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `setPassword on password-only mode preserves device keys`() {
        manager.initializeWithPassword("test_password")
        val key1 = manager.getMasterKeyForSession()
        manager.setPassword("new_password")
        manager.lock()
        val key2 = manager.getMasterKeyForSession()
        assertNotNull(key1)
        assertNotNull(key2)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun `changePassword with correct old and new returns true`() {
        manager.initializeWithPassword("old_password")
        assertTrue(manager.changePassword("old_password", "new_password"))
    }

    @Test
    fun `changePassword with wrong old password returns false`() {
        manager.initializeWithPassword("old_password")
        assertFalse(manager.changePassword("wrong_password", "new_password"))
    }

    @Test
    fun `verifyPassword with new password works after change`() {
        manager.initializeWithPassword("old_password")
        manager.changePassword("old_password", "new_password")
        assertTrue(manager.verifyPassword("new_password"))
    }

    @Test
    fun `verifyPassword with old password fails after change`() {
        manager.initializeWithPassword("old_password")
        manager.changePassword("old_password", "new_password")
        assertFalse(manager.verifyPassword("old_password"))
    }

    @Test
    fun `disablePassword returns true`() {
        manager.initializeWithPassword("test_password")
        assertTrue(manager.disablePassword())
    }

    @Test
    fun `isPasswordSet returns false after disable`() {
        manager.initializeWithPassword("test_password")
        manager.disablePassword()
        assertFalse(manager.isPasswordSet())
    }

    @Test
    fun `getMasterKeyForSession still works after disable`() {
        manager.initializeWithPassword("test_password")
        manager.disablePassword()
        manager.lock()
        val key = manager.getMasterKeyForSession()
        assertNotNull(key)
        assertEquals(32, key!!.size)
    }

    @Test
    fun `double initializeWithPassword returns false`() {
        manager.initializeWithPassword("test_password")
        assertFalse(manager.initializeWithPassword("another_password"))
    }

    @Test
    fun `setPassword without init returns false`() {
        assertFalse(manager.setPassword("test_password"))
    }

    @Test
    fun `disablePassword without password set returns true`() {
        manager.initializeDeviceKeyMode()
        assertTrue(manager.disablePassword())
    }

    @Test
    fun `full password lifecycle survives simulated app restart`() {
        manager.initializeWithPassword("test_password123")
        val originalKey = manager.getMasterKeyForSession()
        assertNotNull(originalKey)

        val context = RuntimeEnvironment.getApplication().applicationContext
        val freshManager = EncryptionManager(context, mockHasher)

        assertFalse(freshManager.isFirstLaunch())
        assertTrue(freshManager.isPasswordSet())

        assertTrue(freshManager.verifyPassword("test_password123"))

        val restoredKey = freshManager.getMasterKeyForSession()
        assertNotNull(restoredKey)
        assertEquals(32, restoredKey!!.size)

        assertArrayEquals(originalKey, restoredKey)
    }
}

class TestKeyStoreCryptographer : KeyStoreCryptographer {
    private val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        return Pair(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
    }
}
