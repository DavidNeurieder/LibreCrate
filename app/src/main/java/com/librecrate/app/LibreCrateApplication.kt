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
    suspend fun openVault(): Boolean {
        val masterKey = encryptionManager.getMasterKeyForSession() ?: return false
        return vaultRepository.open(masterKey)
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        encryptionManager = EncryptionManager(this)
        vaultRepository = VaultRepository(this)
        registerActivityLifecycleCallbacks(ActivityLifecycleLockCallbacks(encryptionManager, this))
        ErrorLogger.installGlobalHandler(this)
    }
    companion object {
        lateinit var instance: LibreCrateApplication
            private set
        private const val TAG = "LibreCrateApp"
    }
}
private class ActivityLifecycleLockCallbacks(
    private val encryptionManager: EncryptionManager,
    private val app: LibreCrateApplication,
) : Application.ActivityLifecycleCallbacks {
    private val activityCount = AtomicInteger(0)
    override fun onActivityStarted(activity: Activity) {
        activityCount.incrementAndGet()
    }
    override fun onActivityStopped(activity: Activity) {
        if (activityCount.decrementAndGet() <= 0) {
            encryptionManager.lock()
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
