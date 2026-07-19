package com.phoneguard.ui.screens.antitheft

import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.domain.shouldersurfer.ShoulderSurferUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AntiTheftViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: AntiTheftViewModel
    private val preferencesManager: PreferencesManager = mockk()
    private val shoulderSurferUseCase: ShoulderSurferUseCase = mockk()
    private val context: android.content.Context = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { preferencesManager.hasPinSet } returns flowOf(false)
        every { preferencesManager.backupNumber } returns flowOf("")
        every { preferencesManager.isSimLockEnabled } returns flowOf(false)
        every { preferencesManager.isPhotoOnFailedAttemptsEnabled } returns flowOf(false)
        every { preferencesManager.isRemoteAlarmEnabled } returns flowOf(false)
        every { preferencesManager.isShoulderSurferEnabled } returns flowOf(false)
        every { preferencesManager.isPro } returns flowOf(true)
        every { context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) } returns mockk(relaxed = true)
        viewModel = AntiTheftViewModel(preferencesManager, shoulderSurferUseCase, context)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState has default values`() = runTest {
        val state = viewModel.uiState.value
        assertFalse(state.hasPin)
        assertEquals("", state.backupNumber)
        assertFalse(state.isDeviceAdminEnabled)
        assertFalse(state.isSimLockEnabled)
        assertFalse(state.isPhotoEnabled)
        assertFalse(state.isRemoteAlarmEnabled)
        assertFalse(state.isShoulderSurferEnabled)
        assertTrue(state.isPro)
    }

    @Test
    fun `setPin calls savePinCode`() = runTest {
        viewModel.setPin("1234")
        coVerify { preferencesManager.savePinCode("1234") }
    }

    @Test
    fun `validatePin returns true for valid pin`() {
        assertTrue(viewModel.validatePin("1234"))
        assertTrue(viewModel.validatePin("123456"))
        assertFalse(viewModel.validatePin("123"))
        assertFalse(viewModel.validatePin("abcd"))
        assertFalse(viewModel.validatePin(""))
    }

    @Test
    fun `validatePhoneNumber returns true for valid number`() {
        assertTrue(viewModel.validatePhoneNumber("+79991234567"))
        assertTrue(viewModel.validatePhoneNumber("89991234567"))
        assertFalse(viewModel.validatePhoneNumber(""))
        assertFalse(viewModel.validatePhoneNumber("abc"))
    }

    @Test
    fun `playAlarm sets isAlarmPlaying true`() = runTest {
        viewModel.playAlarm()
        assertTrue(viewModel.uiState.value.isAlarmPlaying)
        viewModel.stopAlarm()
    }

    @Test
    fun `stopAlarm sets isAlarmPlaying false`() = runTest {
        viewModel.playAlarm()
        viewModel.stopAlarm()
        assertFalse(viewModel.uiState.value.isAlarmPlaying)
    }
}
