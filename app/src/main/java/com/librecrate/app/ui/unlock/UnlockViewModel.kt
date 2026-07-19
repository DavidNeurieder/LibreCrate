package com.librecrate.app.ui.unlock

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librecrate.app.LibreCrateApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UnlockViewModel @JvmOverloads constructor(
    application: Application,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {
    private val app = application as LibreCrateApplication
    private val encryptionManager = app.encryptionManager

    var password: String by mutableStateOf("")
        private set

    var error: String? by mutableStateOf(null)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    val isPasswordSet: Boolean get() = encryptionManager.isPasswordSet()

    fun onPasswordChange(value: String) {
        password = value; error = null
    }

    fun unlock(onSuccess: () -> Unit) {
        if (password.length < 6) { error = "Password must be at least 6 characters"; return }
        isLoading = true
        viewModelScope.launch(defaultDispatcher) {
            val verified = try {
                encryptionManager.verifyPassword(password)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = "Error: ${e.message}" }; null
            }
            withContext(Dispatchers.Main) {
                if (verified == true) {
                    val vaultOpened = app.openVault()
                    if (!vaultOpened) error = "Failed to open database"
                    else onSuccess()
                } else if (verified == false) {
                    error = "Wrong password"
                }
                isLoading = false
            }
        }
    }

    fun clearError() { error = null }
}
