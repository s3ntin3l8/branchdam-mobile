package com.branchdam.mobile.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.branchdam.mobile.ui.theme.BranchDamTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        // Suppress engine handshake
        SettingsViewModel.testConnectionFn = { true }

        val application = ApplicationProvider.getApplicationContext<Application>()
        viewModel = SettingsViewModel(application)
    }

    @Test
    fun testNavigationReset_ReturnsToCategories() {
        composeTestRule.setContent {
            BranchDamTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }

        // Initially on Categories page
        composeTestRule.onNodeWithText("Connection").assertIsDisplayed()

        // Navigate to Appearance
        composeTestRule.onNodeWithText("Appearance").performClick()

        // Verify we are on Appearance page
        composeTestRule.onNodeWithText("Theme").assertIsDisplayed()

        // Trigger reset via ViewModel
        viewModel.triggerNavigationReset()

        // Verify we are back on Categories page
        composeTestRule.onNodeWithText("Connection").assertIsDisplayed()
        composeTestRule.onNodeWithText("Theme").assertDoesNotExist()
    }

    @Test
    fun testInitialComposition_WithExistingTrigger_ResetsToCategories() {
        // Pre-increment reset trigger before setContent.
        // This simulates switching back to the Settings tab where the
        // ViewModel is activity-scoped and already has an incremented counter.
        viewModel.triggerNavigationReset()

        composeTestRule.setContent {
            BranchDamTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }

        // LaunchedEffect should run on first composition because
        // resetTrigger (1) > lastProcessedResetTrigger (0).
        composeTestRule.onNodeWithText("Connection").assertIsDisplayed()
    }
}
