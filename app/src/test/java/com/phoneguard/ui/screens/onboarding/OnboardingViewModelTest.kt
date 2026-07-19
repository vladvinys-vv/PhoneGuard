package com.phoneguard.ui.screens.onboarding

import com.phoneguard.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: OnboardingViewModel
    private val preferencesManager: PreferencesManager = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { preferencesManager.setFirstLaunchDone() } returns Unit
        viewModel = OnboardingViewModel(preferencesManager)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial isCompleted is false`() = runTest {
        assertEquals(false, viewModel.isCompleted.value)
    }

    @Test
    fun `completeOnboarding sets isCompleted to true`() = runTest {
        viewModel.completeOnboarding()
        assertEquals(true, viewModel.isCompleted.value)
    }
}