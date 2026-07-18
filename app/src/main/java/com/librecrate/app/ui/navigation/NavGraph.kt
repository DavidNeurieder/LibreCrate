package com.librecrate.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.librecrate.app.ui.collection.CollectionScreen
import com.librecrate.app.ui.library.LibraryScreen
import com.librecrate.app.ui.export.ExportScreen
import com.librecrate.app.ui.settings.SettingsScreen
import com.librecrate.app.ui.tag.TagScreen
import com.librecrate.app.ui.unlock.PasswordSetupScreen
import com.librecrate.app.ui.unlock.UnlockScreen
import com.librecrate.app.ui.viewer.ViewerScreen

object Routes {
    const val UNLOCK = "unlock"
    const val PASSWORD_SETUP = "password_setup"
    const val LIBRARY = "library"
    const val VIEWER = "viewer/{documentId}?isNewNote={isNewNote}&pageNumber={pageNumber}"
    const val SETTINGS = "settings"
    const val EXPORT = "export"
    const val COLLECTIONS = "collections"
    const val TAGS = "tags"

    fun viewer(documentId: String, pageNumber: Int = -1) = "viewer/$documentId?pageNumber=$pageNumber"
    fun newNote() = "viewer/${java.util.UUID.randomUUID()}?isNewNote=true"
}

@Composable
fun LibreCrateNavGraph(
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
                onDocumentClickWithPage = { docId, pageNumber ->
                    navController.navigate(Routes.viewer(docId, pageNumber))
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onNewNoteClick = {
                    navController.navigate(Routes.newNote())
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
                onNavigateToExport = { navController.navigate(Routes.EXPORT) },
            )
        }
        composable(Routes.EXPORT) {
            ExportScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("isNewNote") { type = NavType.BoolType; defaultValue = false },
                navArgument("pageNumber") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { backStackEntry ->
            ViewerScreen(
                documentId = backStackEntry.arguments?.getString("documentId") ?: "",
                isNewNote = backStackEntry.arguments?.getBoolean("isNewNote") ?: false,
                targetPage = backStackEntry.arguments?.getInt("pageNumber") ?: -1,
                onBack = { navController.popBackStack() },
                onDocumentNotFound = { navController.popBackStack() },
            )
        }
    }
}
