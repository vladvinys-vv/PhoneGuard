package com.phoneguard.ui.screens.callblocker

import com.phoneguard.data.local.BlockedLogDao
import com.phoneguard.data.local.BlockedNumberDao
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.util.CsvImportHelper
import com.phoneguard.util.PhoneNumberNormalizer
import io.mockk.coEvery
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallBlockerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: CallBlockerViewModel
    private val preferencesManager: PreferencesManager = mockk()
    private val appDatabase: com.phoneguard.data.local.AppDatabase = mockk()
    private val context: android.content.Context = mockk(relaxed = true)
    private val csvImportHelper: CsvImportHelper = mockk(relaxed = true)
    private val blockedNumberDao: BlockedNumberDao = mockk(relaxed = true)
    private val blockedLogDao: BlockedLogDao = mockk(relaxed = true)
    private val phoneNumberNormalizer = PhoneNumberNormalizer(context)

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { preferencesManager.blockUnknownNumbers } returns flowOf(false)
        every { preferencesManager.blockHiddenNumbers } returns flowOf(false)
        every { preferencesManager.blockInternationalNumbers } returns flowOf(false)
        every { appDatabase.blockedNumberDao() } returns blockedNumberDao
        every { appDatabase.blockedLogDao() } returns blockedLogDao
        coEvery { blockedNumberDao.getPagedBlacklist(any(), any()) } returns emptyList()
        coEvery { blockedNumberDao.getBlacklistCount() } returns 0
        coEvery { blockedNumberDao.getPagedWhitelist(any(), any()) } returns emptyList()
        coEvery { blockedNumberDao.getWhitelistCount() } returns 0
        coEvery { blockedLogDao.getPagedBlockedLogs(any(), any()) } returns emptyList()
        coEvery { blockedLogDao.getBlockedLogsCount() } returns 0
        viewModel = CallBlockerViewModel(preferencesManager, appDatabase, context, csvImportHelper, phoneNumberNormalizer)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial block rules are false`() = runTest {
        assertEquals(false, viewModel.blockUnknown.value)
        assertEquals(false, viewModel.blockHidden.value)
        assertEquals(false, viewModel.blockInternational.value)
    }

    @Test
    fun `invalid number is rejected before touching the database`() = runTest {
        viewModel.addNumber("not-a-number", "Bogus", false)
        assertEquals(CallBlockerViewModel.ERROR_INVALID_NUMBER, viewModel.error.value)
    }

    @Test
    fun `valid number is inserted into the blacklist`() = runTest {
        // Раньше тест ждал второго события от `blacklist`, хотя DAO замокан и
        // список не меняется: Turbine просто истекал по таймауту. Проверяем
        // то, что действительно происходит — запись в DAO.
        viewModel.addNumber("+79991234567", "Spam", false)

        assertNull(viewModel.error.value)
        coVerify {
            blockedNumberDao.insertBlockedNumber(
                match {
                    it.phoneNumber == "+79991234567" && it.name == "Spam" && !it.isWhitelist
                }
            )
        }
    }
}
