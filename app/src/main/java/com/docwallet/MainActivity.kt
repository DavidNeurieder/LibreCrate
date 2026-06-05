package com.docwallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.docwallet.ui.navigation.DocWalletNavGraph
import com.docwallet.ui.navigation.Routes

class MainActivity : ComponentActivity() {
    private val pendingShareUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleShareIntent(intent)

        val app = application as DocWalletApplication

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    var isReady by remember { mutableStateOf(false) }
                    var startDestination by remember { mutableStateOf(Routes.LIBRARY) }

                    LaunchedEffect(Unit) {
                        if (app.encryptionManager.isFirstLaunch()) {
                            startDestination = Routes.PASSWORD_SETUP
                        } else if (app.encryptionManager.isPasswordSet()) {
                            startDestination = Routes.UNLOCK
                        } else {
                            app.encryptionManager.getMasterKeyForSession()
                            startDestination = Routes.LIBRARY
                        }
                        isReady = true
                    }

                    if (isReady) {
                        DocWalletNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            onUnlocked = { /* handled in NavGraph */ },
                            pendingImportUris = pendingShareUris.value,
                            onPendingImportConsumed = { pendingShareUris.value = emptyList() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    pendingShareUris.value = listOf(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    pendingShareUris.value = uris.filterNotNull()
                }
            }
        }
    }
}
