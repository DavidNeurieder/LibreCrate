package com.docwallet

import android.app.Application
import com.docwallet.data.encryption.EncryptionManager

class DocWalletApplication : Application() {
    lateinit var encryptionManager: EncryptionManager
        private set

    override fun onCreate() {
        super.onCreate()
        encryptionManager = EncryptionManager(this)
    }
}
