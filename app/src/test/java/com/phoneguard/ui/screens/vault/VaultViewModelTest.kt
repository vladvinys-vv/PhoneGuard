package com.phoneguard.ui.screens.vault

import app.cash.turbine.test
import com.phoneguard.data.repository.VaultRepository
import com.phoneguard.data.transfer.VaultTransferManager
import com.phoneguard.model.VaultItem
import com.phoneguard.model.VaultItemCategory
import com.phoneguard.vault.VaultArchiveFormat
import io.mockk.coEvery
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
import java.io.File
import java.io.IOException
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: VaultViewModel
    private val repository: VaultRepository = mockk()
    private val transferManager: VaultTransferManager = mockk(relaxed = true)

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { repository.allItems } returns flowOf(emptyList())
        viewModel = VaultViewModel(
            repository,
            mockk(relaxed = true), // Context
            mockk(relaxed = true), // AnalyticsHelper
            mockk(relaxed = true), // VaultSession
            transferManager
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `initial vault is locked`() = runTest {
        assertEquals(false, viewModel.isUnlocked.value)
    }

    @Test
    fun `initial authError is null`() = runTest {
        assertNull(viewModel.authError.value)
    }

    @Test
    fun `clearAuthError sets authError to null`() = runTest {
        viewModel.clearAuthError()
        assertNull(viewModel.authError.value)
    }

    @Test
    fun `allItems emits repository list`() = runTest {
        val items = listOf(VaultItem(id = 1, fileName = "file1", fileSize = 100, mimeType = "image/jpeg", encryptionKeyAlias = "key1", createdAt = 0, category = VaultItemCategory.IMAGE))
        every { repository.allItems } returns flowOf(items)
        // allItems берётся из репозитория в момент создания ViewModel — пересоздаём его.
        viewModel = VaultViewModel(
            repository,
            mockk(relaxed = true), // Context
            mockk(relaxed = true), // AnalyticsHelper
            mockk(relaxed = true), // VaultSession
            transferManager
        )

        viewModel.allItems.test {
            assertEquals(items, awaitItem())
        }
    }

    @Test
    fun `previewItem reports failure when decryption fails`() = runTest {
        val item = VaultItem(
            id = 1,
            fileName = "broken.jpg",
            fileSize = 10,
            mimeType = "image/jpeg",
            encryptionKeyAlias = "key1",
            category = VaultItemCategory.IMAGE
        )
        coEvery { repository.decryptFileFromVault(item) } throws IOException("bad tag")

        viewModel.previewItem(item)

        assertEquals(VaultError.PreviewFailure, viewModel.previewError.value)
        assertNull("интент не должен создаваться при ошибке", viewModel.previewIntent.value)
    }

    @Test
    fun `clearPreviewError resets error`() = runTest {
        viewModel.clearPreviewError()
        assertNull(viewModel.previewError.value)
    }

    @Test
    fun `export reports the saved archive path`() = runTest {
        val archive = File("/exports/phoneguard_vault.pgvault")
        coEvery { transferManager.export(any()) } returns
            VaultTransferManager.ExportOutcome.VaultExportDone(items = 2, file = archive)

        viewModel.exportVault("lange Passphrase".toCharArray())

        assertEquals(
            VaultViewModel.VaultTransferEvent.VaultTransferSaved(2, archive.absolutePath),
            viewModel.transferEvent.value
        )
        viewModel.consumeTransferEvent()
        assertNull(viewModel.transferEvent.value)
    }

    @Test
    fun `refused archive keeps its reason for the screen`() = runTest {
        coEvery { transferManager.import(any(), any()) } returns
            VaultTransferManager.ImportOutcome.VaultImportRefused(
                VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT
            )

        viewModel.importVault(mockk(relaxed = true), "wrong horse battery".toCharArray())

        // Код, а не текст: экран сам решает, как это сказать на своём языке.
        assertEquals(
            VaultViewModel.VaultTransferEvent.VaultTransferRefused(
                VaultArchiveFormat.Rejection.WRONG_PASSPHRASE_OR_CORRUPT
            ),
            viewModel.transferEvent.value
        )
    }

    @Test
    fun `empty vault is reported separately from a failure`() = runTest {
        coEvery { transferManager.export(any()) } returns
            VaultTransferManager.ExportOutcome.VaultExportEmpty

        viewModel.exportVault("lange Passphrase".toCharArray())

        assertEquals(
            VaultViewModel.VaultTransferEvent.VaultTransferEmpty,
            viewModel.transferEvent.value
        )
    }
}
