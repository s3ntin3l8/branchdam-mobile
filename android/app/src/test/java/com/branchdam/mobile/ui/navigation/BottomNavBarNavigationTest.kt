package com.branchdam.mobile.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.branchdam.mobile.ui.theme.BranchDamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomNavBarNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBottomNavBar_ClickSettings_TriggersCallback() {
        var navigatedRoute: String? = null

        composeTestRule.setContent {
            BranchDamTheme {
                BottomNavBar(
                    currentRoute = Screen.Lineage.route,
                    onNavigate = { navigatedRoute = it }
                )
            }
        }

        // Click on Settings
        composeTestRule.onNodeWithText("Settings").performClick()

        // Verify callback was triggered with Settings route
        assertEquals(Screen.Settings.route, navigatedRoute)
    }
}
