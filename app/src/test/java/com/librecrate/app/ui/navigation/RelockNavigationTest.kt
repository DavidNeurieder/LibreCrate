package com.librecrate.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation behavior remains unchanged when an explicit lock/unlock navigation occurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelockNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `relocking clears the back stack and lands on the unlock screen`() {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.LIBRARY) {
                    composable(Routes.LIBRARY) { Box { Text("library") } }
                    composable(Routes.SETTINGS) { Box { Text("settings") } }
                    composable(Routes.UNLOCK) { Box { Text("unlock") } }
                }
            }
        }
        composeTestRule.waitForIdle()

        // The user is browsing: library -> settings.
        composeTestRule.runOnIdle { navController.navigate(Routes.SETTINGS) }
        composeTestRule.waitForIdle()

        // Navigate to unlock and rebuild the stack.
        composeTestRule.runOnIdle {
            navController.navigate(Routes.UNLOCK) {
                popUpTo(navController.graph.id)
                launchSingleTop = true
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(Routes.UNLOCK, navController.currentBackStackEntry?.destination?.route)
        assertNull(navController.previousBackStackEntry?.destination?.route)
    }

    @Test
    fun `relocking from the unlock screen itself is a no-op`() {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            MaterialTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.UNLOCK) {
                    composable(Routes.LIBRARY) { Box { Text("library") } }
                    composable(Routes.UNLOCK) { Box { Text("unlock") } }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            navController.navigate(Routes.UNLOCK) {
                popUpTo(navController.graph.id)
                launchSingleTop = true
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(Routes.UNLOCK, navController.currentBackStackEntry?.destination?.route)
        assertNull(navController.previousBackStackEntry?.destination?.route)
    }
}
