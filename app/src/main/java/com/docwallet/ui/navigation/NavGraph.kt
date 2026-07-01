package com.docwallet.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.docwallet.ui.collection.CollectionScreen
import com.docwallet.ui.library.LibraryScreen
import com.docwallet.ui.settings.SettingsScreen
import com.docwallet.ui.tag.TagScreen
import com.docwallet.ui.unlock.PasswordSetupScreen
import com.docwallet.ui.unlock.UnlockScreen
import com.docwallet.ui.viewer.ViewerScreen

object Routes {
    const val UNLOCK = "unlock"
    const val PASSWORD_SETUP = "password_setup"
    const val LIBRARY = "library"
    const val VIEWER = "viewer/{documentId}"
    const val SETTINGS = "settings"
    const val COLLECTIONS = "collections"
    const val TAGS = "tags"

    fun viewer(documentId: String) = "viewer/$documentId"
}

@Composable
fun DocWalletNavGraph(
    navController: NavHostController,
    startDestination: String,
    onUnlocked: () -> Unit,
    pendingImportUris: List<Uri> = emptyList(),
    onPendingImportConsumed: () -> Unit = {},
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
            LibraryScreen(
                onDocumentClick = { navController.navigate(Routes.viewer(it)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onNewNoteClick = {
                    val newId = java.util.UUID.randomUUID().toString()
                    navController.navigate(Routes.viewer(newId))
                },
                pendingImportUris = pendingImportUris,
                onPendingImportConsumed = onPendingImportConsumed,
            )
        }
        composable(Routes.COLLECTIONS) {
            CollectionScreen(
                onBack = { navController.popBackStack() },
                onCollectionClick = { },
            )
        }
        composable(Routes.TAGS) {
            TagScreen(
                onBack = { navController.popBackStack() },
                onTagClick = { },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onCollectionsClick = { navController.navigate(Routes.COLLECTIONS) },
                onTagsClick = { navController.navigate(Routes.TAGS) },
            )
        }
        composable(Routes.VIEWER) { backStackEntry ->
            ViewerScreen(
                documentId = backStackEntry.arguments?.getString("documentId") ?: "",
                onBack = { navController.popBackStack() },
                onDocumentNotFound = { navController.popBackStack() },
            )
        }
    }
}
