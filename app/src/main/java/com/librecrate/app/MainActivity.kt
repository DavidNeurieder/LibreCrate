package com.librecrate.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.librecrate.app.data.AppPreferencesStore
import com.librecrate.app.data.PinLockManager
import com.librecrate.app.ui.navigation.LibreCrateNavGraph
import com.librecrate.app.ui.navigation.Routes

class MainActivity : ComponentActivity() {
    private val pendingShareUris = mutableStateOf<List<Uri>>(emptyList())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "screenshots_enabled") {
            updateScreenCaptureFlag()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateScreenCaptureFlag()
        getSharedPreferences("app_preferences", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        handleShareIntent(intent)

        val app = application as LibreCrateApplication

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
                        } else if (app.encryptionManager.getMasterKeyForSession() != null) {
                            startDestination = Routes.LIBRARY
                        } else if (app.encryptionManager.isPasswordSet()) {
                            startDestination = Routes.UNLOCK
                        } else {
                            startDestination = Routes.LIBRARY
                        }
                        isReady = true
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isReady) {
                            LibreCrateNavGraph(
                                navController = navController,
                                startDestination = startDestination,
                                onUnlocked = { /* handled in NavGraph */ },
                                pendingImportUris = pendingShareUris.value,
                                onPendingImportConsumed = { pendingShareUris.value = emptyList() },
                            )
                        }

                        PinUnlockOverlay()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("app_preferences", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun updateScreenCaptureFlag() {
        if (AppPreferencesStore.isScreenshotsEnabled(this)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    if (uri != null && uri.scheme == "content") {
                        pendingShareUris.value = listOf(uri)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null && uri.scheme == "content") {
                        pendingShareUris.value = listOf(uri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        ?: emptyList()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        ?: emptyList()
                }
                pendingShareUris.value = uris.filter { it.scheme == "content" }
            }
        }
    }
}

@Composable
private fun PinUnlockOverlay() {
    if (!PinLockManager.isLocked) return

    val context = LocalContext.current
    val keyguardManager = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    val pinLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            PinLockManager.unlock()
        }
    }

    LaunchedEffect(Unit) {
        if (PinLockManager.isLocked && keyguardManager.isKeyguardSecure) {
            val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock LibreCrate", null)
            if (intent != null) {
                pinLauncher.launch(intent)
            }
        }
    }

    val lifecycle = (context as LifecycleOwner).lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && PinLockManager.isLocked) {
                if (keyguardManager.isKeyguardSecure) {
                    val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock LibreCrate", null)
                    if (intent != null) {
                        pinLauncher.launch(intent)
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LibreCrate",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (keyguardManager.isKeyguardSecure) {
                Text(
                    text = "App is locked",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock LibreCrate", null)
                    if (intent != null) {
                        pinLauncher.launch(intent)
                    }
                }) {
                    Text("Unlock")
                }
            } else {
                Text(
                    text = "Set a device lock screen in your phone settings to use PIN lock",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
