package com.docwallet.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.docwallet.DocWalletApplication
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.model.Document
import com.docwallet.domain.BackupProgress
import com.docwallet.vault.crypto.FileEncryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents: StateFlow<List<Document>> = _documents.asStateFlow()

    private val _selectedDocIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedDocIds: StateFlow<Set<String>> = _selectedDocIds.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    init {
        loadDocuments()
    }

    private fun loadDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            _documents.value = app.documentDao.getAllDocumentsOnce()
        }
    }

    fun toggleDocumentSelection(id: String) {
        val current = _selectedDocIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id)
        else current.add(id)
        _selectedDocIds.value = current
    }

    fun selectAllDocuments() {
        _selectedDocIds.value = _documents.value.map { it.id }.toSet()
    }

    fun deselectAllDocuments() {
        _selectedDocIds.value = emptySet()
    }

    fun onExportDocumentsConfirmed(uri: Uri) {
        val selectedIds = _selectedDocIds.value
        if (selectedIds.isEmpty()) {
            _message.value = "No documents selected"
            return
        }

        _isExporting.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val masterKey = encryptionManager.getMasterKeyForSession()
                    if (masterKey == null) {
                        _message.value = "Vault is locked"
                        return@withContext false
                    }

                    val docsToExport = _documents.value.filter { it.id in selectedIds }
                    val total = docsToExport.size
                    val fileEncryptor = FileEncryptor()
                    val nameCounts = mutableMapOf<String, Int>()

                    val tempZip = File(app.cacheDir, "export_${System.currentTimeMillis()}.zip")
                    ZipOutputStream(tempZip.outputStream()).use { zos ->
                        docsToExport.forEachIndexed { i, doc ->
                            val phase = "Decrypting files"
                            val fraction = (i.toFloat() / total) * 0.8f
                            _backupProgress.value = BackupProgress(phase, fraction, "${i + 1} of $total")

                            val encryptedFile = File(doc.filePath)
                            if (!encryptedFile.exists()) {
                                _message.value = "File not found: ${doc.title}"
                                return@withContext false
                            }

                            val encryptedBytes = encryptedFile.readBytes()
                            if (encryptedBytes.size < FileEncryptor.IV_LENGTH) {
                                _message.value = "Corrupted file: ${doc.title}"
                                return@withContext false
                            }

                            val iv = encryptedBytes.copyOfRange(0, FileEncryptor.IV_LENGTH)
                            val ciphertext = encryptedBytes.copyOfRange(FileEncryptor.IV_LENGTH, encryptedBytes.size)
                            val decrypted = fileEncryptor.decryptBytes(ciphertext, masterKey, iv)

                            val name = doc.fileName.ifBlank { doc.title.ifBlank { "document" } }
                            val count = nameCounts.getOrDefault(name, 0)
                            nameCounts[name] = count + 1
                            val entryName = if (count > 0) {
                                val dot = name.lastIndexOf('.')
                                if (dot >= 0) "${name.substring(0, dot)}_$count${name.substring(dot)}"
                                else "${name}_$count"
                            } else name

                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(decrypted)
                            zos.closeEntry()
                        }
                    }

                    _backupProgress.value = BackupProgress("Writing to file", 0.85f)
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        tempZip.inputStream().use { `in` -> `in`.copyTo(out) }
                    } ?: run {
                        _message.value = "Failed to open output file"
                        tempZip.delete()
                        return@withContext false
                    }

                    tempZip.delete()
                    _backupProgress.value = BackupProgress("Export complete", 1.0f)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("SettingsVM", "Export failed", e)
                    _message.value = "Export failed: ${e.localizedMessage ?: "Unknown error"}"
                    false
                }
            }

            _backupProgress.value = null
            _isExporting.value = false
            if (success) {
                deselectAllDocuments()
            }
            _message.value = if (success) "Documents exported successfully" else null
        }
    }

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

    fun refreshDocuments() {
        loadDocuments()
    }

    private fun clearFields() {
        currentPassword.value = ""
        newPassword.value = ""
        confirmPassword.value = ""
    }
}
