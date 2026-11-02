package com.phoneguard.vault

import com.phoneguard.model.VaultItemCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Категория файла сейфа по MIME-типу.
 *
 * Раньше это был `when` внутри `EncryptionManager.encryptFileToGallery` (Context + ContentResolver
 * + AndroidKeyStore), то есть не проверялся никогда. Заодно закрепляем исправленный баг:
 * `VaultItem.isImage` не заполнялся вовсе, и в базе у всех файлов стояло `false`.
 */
class VaultItemKindTest {

    @Test
    fun imagesLandInImageCategory() {
        assertEquals(VaultItemCategory.IMAGE, VaultItemKind.categoryOf("image/png"))
        assertEquals(VaultItemCategory.IMAGE, VaultItemKind.categoryOf("image/jpeg"))
        assertTrue(VaultItemKind.isImage("image/webp"))
    }

    @Test
    fun videosLandInVideoCategory() {
        assertEquals(VaultItemCategory.VIDEO, VaultItemKind.categoryOf("video/mp4"))
        assertFalse(VaultItemKind.isImage("video/mp4"))
    }

    @Test
    fun pdfAndTextAreDocuments() {
        assertEquals(VaultItemCategory.DOCUMENT, VaultItemKind.categoryOf("application/pdf"))
        assertEquals(VaultItemCategory.DOCUMENT, VaultItemKind.categoryOf("text/plain"))
        assertEquals(VaultItemCategory.DOCUMENT, VaultItemKind.categoryOf("text/csv"))
    }

    @Test
    fun unknownTypesAreOther() {
        assertEquals(VaultItemCategory.OTHER, VaultItemKind.categoryOf("application/octet-stream"))
        assertEquals(VaultItemCategory.OTHER, VaultItemKind.categoryOf("application/zip"))
    }

    @Test
    fun missingTypeIsOtherInsteadOfCrash() {
        // `ContentResolver.getType()` может вернуть null (или пустую строку): в старом коде
        // подставлялся `application/octet-stream`, но полагаться на это в правиле нельзя.
        assertEquals(VaultItemCategory.OTHER, VaultItemKind.categoryOf(null))
        assertEquals(VaultItemCategory.OTHER, VaultItemKind.categoryOf(""))
        assertEquals(VaultItemCategory.OTHER, VaultItemKind.categoryOf("   "))
    }

    @Test
    fun typeIsCaseInsensitive() {
        // RFC 2045: регистр в MIME-типе не значим. Некоторые поставщики отдают `Image/JPEG`,
        // и раньше такой файл попадал в OTHER (то есть картинка не считалась картинкой).
        assertEquals(VaultItemCategory.IMAGE, VaultItemKind.categoryOf("Image/JPEG"))
        assertEquals(VaultItemCategory.VIDEO, VaultItemKind.categoryOf("VIDEO/MP4"))
    }

    @Test
    fun parametersAfterSemicolonAreIgnored() {
        assertEquals(VaultItemCategory.IMAGE, VaultItemKind.categoryOf("image/png; charset=binary"))
        assertEquals(VaultItemCategory.DOCUMENT, VaultItemKind.categoryOf("text/plain; charset=utf-8"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(VaultItemCategory.IMAGE, VaultItemKind.categoryOf("  image/png  "))
    }

    @Test
    fun normalizeKeepsOnlyTheType() {
        assertEquals("image/png", VaultItemKind.normalize("image/png; charset=binary"))
        assertEquals("text/plain", VaultItemKind.normalize("TEXT/Plain"))
        assertEquals("", VaultItemKind.normalize(null))
    }

    @Test
    fun isImageMatchesTheCategory() {
        // Инвариант: два поля модели (`isImage`, `category`) считаются из одного места,
        // иначе они разъедутся — именно так `isImage` и оказался всегда false.
        listOf(
            "image/png", "image/jpeg", "video/mp4", "application/pdf",
            "text/plain", "application/zip", ""
        ).forEach { mime ->
            assertEquals(
                "isImage должен совпадать с категорией для $mime",
                VaultItemKind.categoryOf(mime) == VaultItemCategory.IMAGE,
                VaultItemKind.isImage(mime)
            )
        }
    }
}
