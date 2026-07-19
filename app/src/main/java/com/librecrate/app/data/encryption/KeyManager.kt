package com.librecrate.app.data.encryption

interface KeyManager {
    fun isPasswordSet(): Boolean
    fun isFirstLaunch(): Boolean
    fun initializeDeviceKeyMode()
    fun initializeWithPassword(password: String): Boolean
    fun getMasterKeyForSession(): ByteArray?
    fun setPassword(password: String): Boolean
    fun verifyPassword(password: String): Boolean
    fun changePassword(oldPassword: String, newPassword: String): Boolean
    fun disablePassword(): Boolean
    fun lock()
}
