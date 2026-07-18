package com.librecrate.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.librecrate.app.data.AppPreferencesStore
import com.librecrate.app.data.PinLockManager
import com.librecrate.app.data.db.CollectionDao
import com.librecrate.app.data.db.LibreCrateDatabase
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.db.TagDao
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.data.import.DocumentImporter
import com.librecrate.app.domain.BackupManager
import com.librecrate.app.vault.crypto.FileEncryptor
import java.util.concurrent.atomic.AtomicInteger


class LibreCrateApplication : Application() {
    lateinit var encryptionManager: EncryptionManager
        private set

    private var database: LibreCrateDatabase? = null
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
            val newDb = LibreCrateDatabase.create(this, passphrase)
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
        val oldDb = database
        val passphrase = encryptionManager.getMasterKeyForSession()
            ?: return false
        return try {
            val newDb = LibreCrateDatabase.create(this, passphrase)
            database = newDb
            _documentDao = newDb.documentDao()
            _collectionDao = newDb.collectionDao()
            _tagDao = newDb.tagDao()
            try { oldDb?.close() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reopen database", e)
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        encryptionManager = EncryptionManager(this)
        registerActivityLifecycleCallbacks(ActivityLifecycleLockCallbacks(encryptionManager, this))
        if (AppPreferencesStore.isPinEnabled(this)) {
            PinLockManager.lock()
        }
    }

    companion object {
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
            if (AppPreferencesStore.isPinEnabled(app)) {
                PinLockManager.lock()
            } else {
                encryptionManager.lock()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
