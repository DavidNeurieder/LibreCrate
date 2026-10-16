package com.librecrate.app
import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.data.import.DocumentImporter
import com.librecrate.app.data.vault.VaultRepository
import com.librecrate.app.domain.BackupManager
import com.librecrate.app.util.ErrorLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class LibreCrateApplication : Application() {
    lateinit var encryptionManager: EncryptionManager
        private set
    lateinit var vaultRepository: VaultRepository
        private set
    val documentImporter: DocumentImporter by lazy {
        DocumentImporter(this, vaultRepository)
    }
    val backupManager: BackupManager by lazy {
        BackupManager(this, encryptionManager, vaultRepository)
    }

    // True once the session has been locked (the app left the foreground) until
    // it is opened again. Observed by MainActivity to re-ask for the password:
    // the Activity is reused on return (launchMode="singleTop"), so it would
    // otherwise stay on the library screen with a closed vault.
    private val _sessionLocked = MutableStateFlow(false)
    val sessionLocked: StateFlow<Boolean> = _sessionLocked.asStateFlow()

    suspend fun openVault(): Boolean {
        val masterKey = encryptionManager.getMasterKeyForSession() ?: return false
        val opened = vaultRepository.open(masterKey)
        if (opened) _sessionLocked.value = false
        return opened
    }

    /**
     * Locks the session: zeroizes the master key, tears down the open SQLCipher
     * connection (PR 6) and marks the session as locked so the UI asks for the
     * password again when the app is brought back to the foreground.
     */
    fun lockSession() {
        encryptionManager.lock()
        vaultRepository.close()
        _sessionLocked.value = true
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        encryptionManager = EncryptionManager(this)
        vaultRepository = VaultRepository(this)
        registerActivityLifecycleCallbacks(ActivityLifecycleLockCallbacks(this))
        ErrorLogger.installGlobalHandler(this)
    }
    companion object {
        lateinit var instance: LibreCrateApplication
            private set
        private const val TAG = "LibreCrateApp"
    }
}
private class ActivityLifecycleLockCallbacks(
    private val app: LibreCrateApplication,
) : Application.ActivityLifecycleCallbacks {
    private val activityCount = AtomicInteger(0)
    override fun onActivityStarted(activity: Activity) {
        activityCount.incrementAndGet()
    }
    override fun onActivityStopped(activity: Activity) {
        if (activityCount.decrementAndGet() <= 0) {
            app.lockSession()
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
