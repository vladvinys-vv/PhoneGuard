package com.phoneguard.vault

import com.phoneguard.model.VaultItemCategory

/**
 * Категория файла в сейфе по его MIME-типу.
 *
 * Вынесено из [EncryptionManager]: там это был `when` внутри метода, который требует `Context`,
 * `ContentResolver` и AndroidKeyStore, поэтому не проверялся ни разу. Заодно исправлено:
 * поле `VaultItem.isImage` **никогда не заполнялось** (всегда оставалось `false`, даже для
 * картинок), из-за чего миграция `category = 'IMAGE' WHERE isImage = 1` была пустышкой,
 * а любой будущий фильтр «только изображения» не работал бы. Теперь `isImage` — следствие
 * категории, и то, и другое считается в одном месте.
 */
internal object VaultItemKind {

    /**
     * MIME-тип без параметров и в нижнем регистре: `image/png; charset=binary` → `image/png`.
     *
     * Регистр не важен по RFC 2045, а некоторые поставщики отдают `Image/JPEG` — сравнение
     * «как есть» отправляло такие файлы в `OTHER`.
     */
    fun normalize(mimeType: String?): String =
        mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    fun categoryOf(mimeType: String?): VaultItemCategory {
        val type = normalize(mimeType)
        return when {
            type.startsWith("image/") -> VaultItemCategory.IMAGE
            type.startsWith("video/") -> VaultItemCategory.VIDEO
            type.startsWith("application/pdf") || type.startsWith("text/") -> VaultItemCategory.DOCUMENT
            else -> VaultItemCategory.OTHER
        }
    }

    /** Картинка ли это — тот же ответ, что `categoryOf(…) == IMAGE`, но без повторов в вызывающем коде. */
    fun isImage(mimeType: String?): Boolean = categoryOf(mimeType) == VaultItemCategory.IMAGE
}
