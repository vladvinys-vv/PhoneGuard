package com.phoneguard.ui.screens.vault

import app.cash.turbine.test
import com.phoneguard.data.repository.VaultRepository
import com.phoneguard.model.VaultItem
import com.phoneguard.model.VaultItemCategory
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
import java.io.IOException
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: VaultViewModel
    private val repository: VaultRepository = mockk()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        every { repository.allItems } returns flowOf(emptyList())
        viewModel = VaultViewModel(repository, mockk(relaxed = true), mockk(relaxed = true))
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
        viewModel = VaultViewModel(repository, mockk(relaxed = true), mockk(relaxed = true))

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
}
