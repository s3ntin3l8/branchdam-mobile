package com.branchdam.mobile.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
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
    fun testTriggerNavigationReset_UpdatesFlow() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = SettingsViewModel(application)

        val initialValue = viewModel.navigationResetTrigger.value

        viewModel.triggerNavigationReset()

        val newValue = viewModel.navigationResetTrigger.value
        assertNotEquals("Trigger should update the timestamp", initialValue, newValue)
    }
}
