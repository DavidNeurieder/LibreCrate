package com.docwallet.vault.crypto

interface KeyManager {
    fun getMasterKeyForSession(): ByteArray?
    fun lock()
    fun isPasswordSet(): Boolean
    fun isFirstLaunch(): Boolean

    fun initializeDeviceKeyMode()
    fun initializeWithPassword(password: String): Boolean
    fun setPassword(password: String): Boolean
    fun verifyPassword(password: String): Boolean
    fun changePassword(oldPassword: String, newPassword: String): Boolean
    fun disablePassword(): Boolean
}
