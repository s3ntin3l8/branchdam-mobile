package com.branchdam.mobile.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelNavigationTest {

    @Before
    fun setUp() {
        SettingsViewModel.testConnectionFn = { true }
    }

    @Test
    fun testTriggerNavigationReset_IncrementsCounter() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = SettingsViewModel(application)

        val initialValue = viewModel.navigationResetTrigger.value

        viewModel.triggerNavigationReset()

        val firstReset = viewModel.navigationResetTrigger.value
        assertEquals("Trigger should increment the counter", initialValue + 1, firstReset)

        viewModel.triggerNavigationReset()
        val secondReset = viewModel.navigationResetTrigger.value
        assertEquals("Second trigger should increment again", firstReset + 1, secondReset)
    }
}
