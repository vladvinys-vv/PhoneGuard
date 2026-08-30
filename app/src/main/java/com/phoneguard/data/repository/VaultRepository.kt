package com.phoneguard.data.repository

import android.net.Uri
import com.phoneguard.data.local.VaultDao
import com.phoneguard.model.VaultItem
import com.phoneguard.vault.EncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultRepository @Inject constructor(
    private val vaultDao: VaultDao,
    private val encryptionManager: EncryptionManager
) {
    val allItems: Flow<List<VaultItem>> = vaultDao.getAllItems()
        .map { entities -> entities.map { it } }

    suspend fun importFile(uri: Uri, deleteOriginal: Boolean): Long =
        importFromGallery(uri, deleteOriginal)

    suspend fun importFromGallery(uri: Uri, deleteOriginal: Boolean): Long = withContext(Dispatchers.IO) {
        val item = encryptionManager.encryptFileToVault(uri, deleteOriginal)
        vaultDao.insertItem(item)
    }

    suspend fun deleteItem(item: VaultItem) = withContext(Dispatchers.IO) {
        encryptionManager.deleteVaultFile(item)
        vaultDao.deleteItem(item)
    }

    suspend fun decryptFileFromVault(item: VaultItem): File? = withContext(Dispatchers.IO) {
        encryptionManager.decryptFileFromVault(item)
    }

    fun decryptItem(item: VaultItem): File? = encryptionManager.decryptFileFromVault(item)
}
