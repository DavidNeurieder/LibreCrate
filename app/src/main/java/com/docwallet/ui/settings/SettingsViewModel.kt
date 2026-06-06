package com.docwallet.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docwallet.DocWalletApplication
import com.docwallet.data.encryption.EncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DocWalletApplication
    private val encryptionManager: EncryptionManager = app.encryptionManager

    val isPasswordSet: Boolean
        get() = encryptionManager.isPasswordSet()

    val currentPassword = MutableStateFlow("")
    val newPassword = MutableStateFlow("")
    val confirmPassword = MutableStateFlow("")

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setPassword() {
        val pwd = newPassword.value
        val confirm = confirmPassword.value

        when {
            pwd.length < 4 -> {
                _message.value = "Password must be at least 4 characters"
                return
            }
            pwd != confirm -> {
                _message.value = "Passwords do not match"
                return
            }
        }

        if (encryptionManager.setPassword(pwd)) {
            _message.value = "Password set successfully"
            clearFields()
        } else {
            _message.value = "Failed to set password"
        }
    }

    fun changePassword() {
        val old = currentPassword.value
        val new = newPassword.value
        val confirm = confirmPassword.value

        when {
            old.length < 4 || new.length < 4 -> {
                _message.value = "Password must be at least 4 characters"
                return
            }
            new != confirm -> {
                _message.value = "New passwords do not match"
                return
            }
        }

        if (encryptionManager.changePassword(old, new)) {
            _message.value = "Password changed successfully"
            clearFields()
        } else {
            _message.value = "Failed to change password. Check your current password."
        }
    }

    fun disablePassword() {
        val pwd = currentPassword.value
        if (pwd.isBlank()) {
            _message.value = "Enter your current password to disable"
            return
        }
        if (!encryptionManager.verifyPassword(pwd)) {
            _message.value = "Wrong password"
            return
        }
        if (encryptionManager.disablePassword()) {
            _message.value = "Password disabled. Using device-level encryption."
            clearFields()
        } else {
            _message.value = "Failed to disable password"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                app.backupManager.exportBackupToUri(uri)
            }
            _message.value = if (success) "Backup exported successfully" else "Backup export failed"
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val password = currentPassword.value.takeIf { it.isNotBlank() }
            val success = withContext(Dispatchers.IO) {
                app.backupManager.importBackupFromUri(uri, password)
            }
            _message.value = if (success) "Backup imported successfully" else "Backup import failed"
        }
    }

    private fun clearFields() {
        currentPassword.value = ""
        newPassword.value = ""
        confirmPassword.value = ""
    }
}
