package com.docwallet.ui.unlock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.docwallet.DocWalletApplication

class UnlockViewModel(application: Application) : AndroidViewModel(application) {
    private val encryptionManager = (application as DocWalletApplication).encryptionManager

    var password: String = ""
        private set

    var error: String? = null
        private set

    var isLoading: Boolean = false
        private set

    val isPasswordSet: Boolean
        get() = encryptionManager.isPasswordSet()

    fun onPasswordChange(value: String) {
        password = value
        error = null
    }

    fun unlock(onSuccess: () -> Unit) {
        if (password.length < 4) {
            error = "Password must be at least 4 characters"
            return
        }

        isLoading = true
        try {
            val verified = encryptionManager.verifyPassword(password)
            if (verified) {
                onSuccess()
            } else {
                error = "Wrong password"
            }
        } catch (e: Exception) {
            error = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    fun clearError() {
        error = null
    }
}
