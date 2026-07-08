package com.docwallet

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.docwallet.data.db.CollectionDao
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.db.DocumentDao
import com.docwallet.data.db.TagDao
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.import.DocumentImporter
import com.docwallet.domain.BackupManager
import com.docwallet.vault.crypto.FileEncryptor
import java.util.concurrent.atomic.AtomicInteger


class DocWalletApplication : Application() {
    lateinit var encryptionManager: EncryptionManager
        private set

    private var database: DocWalletDatabase? = null
    private var _documentDao: DocumentDao? = null
    private var _collectionDao: CollectionDao? = null
    private var _tagDao: TagDao? = null

    val documentDao: DocumentDao get() {
        initializeDatabase()
        return _documentDao ?: throw IllegalStateException("Database not available")
    }
    val collectionDao: CollectionDao get() {
        initializeDatabase()
        return _collectionDao ?: throw IllegalStateException("Database not available")
    }
    val tagDao: TagDao get() {
        initializeDatabase()
        return _tagDao ?: throw IllegalStateException("Database not available")
    }

    val fileEncryptor: FileEncryptor by lazy { FileEncryptor() }

    val documentImporter: DocumentImporter by lazy {
        DocumentImporter(this, documentDao, fileEncryptor, encryptionManager)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(this, encryptionManager, { database })
    }

    @Synchronized
    private fun initializeDatabase(): Boolean {
        if (database != null) return true
        val passphrase = encryptionManager.getMasterKeyForSession()
            ?: return false
        return try {
            val newDb = DocWalletDatabase.create(this, passphrase)
            database = newDb
            _documentDao = newDb.documentDao()
            _collectionDao = newDb.collectionDao()
            _tagDao = newDb.tagDao()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database", e)
            false
        }
    }

    @Synchronized
    fun reopenDatabase(): Boolean {
        val passphrase = encryptionManager.getMasterKeyForSession()
            ?: return false
        return try {
            val newDb = DocWalletDatabase.create(this, passphrase)
            database = newDb
            _documentDao = newDb.documentDao()
            _collectionDao = newDb.collectionDao()
            _tagDao = newDb.tagDao()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reopen database", e)
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        encryptionManager = EncryptionManager(this)
        registerActivityLifecycleCallbacks(ActivityLifecycleLockCallbacks(encryptionManager))
    }

    companion object {
        private const val TAG = "DocWalletApp"
    }
}

private class ActivityLifecycleLockCallbacks(
    private val encryptionManager: EncryptionManager
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
