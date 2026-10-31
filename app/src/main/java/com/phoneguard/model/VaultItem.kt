package com.phoneguard.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_items", indices = [Index(value = ["createdAt"]), Index(value = ["category"])])
data class VaultItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val encryptionKeyAlias: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isImage: Boolean = false,
    val category: VaultItemCategory = VaultItemCategory.OTHER
)
