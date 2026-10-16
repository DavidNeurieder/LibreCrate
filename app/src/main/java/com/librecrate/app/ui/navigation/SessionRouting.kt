package com.librecrate.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Decisions for the unlock/session UI.
 *
 * The vault locks whenever the app leaves the foreground (see
 * `LibreCrateApplication`): the session master key is zeroized and the SQLCipher
 * connection is closed. Coming back therefore has to ask for the password again,
 * even though `MainActivity` is reused (`launchMode="singleTop"`) and would
 * otherwise stay on the library screen with a closed vault.
 */
object SessionRouting {

    /** Destination to start the nav graph on. */
    fun startDestination(isPasswordSet: Boolean, hasSessionKey: Boolean): String = when {
        !isPasswordSet -> Routes.PASSWORD_SETUP
        hasSessionKey -> Routes.LIBRARY
        else -> Routes.UNLOCK
    }

    /**
     * Whether returning to the foreground must show the unlock screen again.
     * `sessionLocked` is set when the vault is torn down and cleared once it is
     * opened again, so this is only true for a *previously unlocked* session.
     */
    fun shouldPromptForPassword(isPasswordSet: Boolean, sessionLocked: Boolean): Boolean =
        isPasswordSet && sessionLocked
}

/**
 * Re-asks for the password when the app returns to the foreground after the
 * vault locked itself in the background.
 */
@Composable
fun PromptForPasswordOnRelock(
    isReady: Boolean,
    sessionLocked: Boolean,
    isPasswordSet: () -> Boolean,
    onPrompt: () -> Unit,
) {
    LaunchedEffect(isReady, sessionLocked) {
        if (isReady && SessionRouting.shouldPromptForPassword(isPasswordSet(), sessionLocked)) {
            onPrompt()
        }
    }
}
