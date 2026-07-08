package com.docwallet.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docwallet.DocWalletApplication
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.domain.BackupProgress
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

    val exportVaultPassword = MutableStateFlow("")
    val importVaultPassword = MutableStateFlow("")

    val showExportPasswordDialog = MutableStateFlow(false)
    val showImportPasswordDialog = MutableStateFlow(false)
    val pendingImportUri = MutableStateFlow<Uri?>(null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _backupProgress = MutableStateFlow<BackupProgress?>(null)
    val backupProgress: StateFlow<BackupProgress?> = _backupProgress.asStateFlow()

    fun setPassword() {
        val pwd = newPassword.value
        val confirm = confirmPassword.value

        when {
            pwd.length < 6 -> {
                _message.value = "Password must be at least 6 characters"
                return
            }
            pwd != confirm -> {
                _message.value = "Passwords do not match"
                return
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            if (encryptionManager.setPassword(pwd)) {
                _message.value = "Password set successfully"
                clearFields()
            } else {
                _message.value = "Failed to set password"
            }
        }
    }

    fun changePassword() {
        val old = currentPassword.value
        val new = newPassword.value
        val confirm = confirmPassword.value

        when {
            old.length < 6 || new.length < 6 -> {
                _message.value = "Password must be at least 6 characters"
                return
            }
            new != confirm -> {
                _message.value = "New passwords do not match"
                return
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            if (encryptionManager.changePassword(old, new)) {
                _message.value = "Password changed successfully"
                clearFields()
            } else {
                _message.value = "Failed to change password. Check your current password."
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun onExportConfirmed(uri: Uri) {
        val password = exportVaultPassword.value

        if (password.isBlank()) {
            _message.value = "Enter your vault password"
            return
        }

        showExportPasswordDialog.value = false
        viewModelScope.launch {
            val success = withContext(Dispatchers.Default) {
                if (!encryptionManager.verifyPassword(password)) {
                    return@withContext false
                }
                withContext(Dispatchers.IO) {
                    app.backupManager.exportBackupToUri(uri, password) { progress ->
                        _backupProgress.value = progress
                    }
                }
            }
            _backupProgress.value = null
            if (success) {
                exportVaultPassword.value = ""
            }
            _message.value = if (success) "Backup exported successfully" else "Backup export failed"
        }
    }

    fun onImportConfirmed() {
        val password = importVaultPassword.value
        val uri = pendingImportUri.value ?: return

        if (password.isBlank()) {
            _message.value = "Enter your vault password"
            return
        }

        showImportPasswordDialog.value = false
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                val ok = app.backupManager.importBackupFromUri(uri, password) { progress ->
                    _backupProgress.value = progress
                }
                if (ok) app.reopenDatabase() else false
            }
            _backupProgress.value = null
            if (success) {
                importVaultPassword.value = ""
                pendingImportUri.value = null
            }
            _message.value = if (success) "Backup imported successfully" else "Backup import failed"
        }
    }

    fun cancelExport() {
        showExportPasswordDialog.value = false
        exportVaultPassword.value = ""
    }

    fun cancelImport() {
        showImportPasswordDialog.value = false
        importVaultPassword.value = ""
        pendingImportUri.value = null
    }

    private fun clearFields() {
        currentPassword.value = ""
        newPassword.value = ""
        confirmPassword.value = ""
    }
}
