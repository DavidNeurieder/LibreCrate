package com.docwallet.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.docwallet.ui.unlock.PasswordSetupScreen
import com.docwallet.ui.unlock.UnlockScreen

object Routes {
    const val UNLOCK = "unlock"
    const val PASSWORD_SETUP = "password_setup"
    const val LIBRARY = "library"
}

@Composable
fun DocWalletNavGraph(
    navController: NavHostController,
    startDestination: String,
    onUnlocked: () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.UNLOCK) {
            UnlockScreen(
                onUnlocked = {
                    onUnlocked()
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.UNLOCK) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PASSWORD_SETUP) {
            PasswordSetupScreen(
                onComplete = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.PASSWORD_SETUP) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LIBRARY) {
            // Placeholder - will be replaced with real library screen
            androidx.compose.material3.Text("Library coming soon")
        }
    }
}
