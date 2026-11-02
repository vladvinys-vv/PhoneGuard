package com.phoneguard.fullscan.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пути из `MediaStore` и правило «APK вне Download».
 *
 * Реальные ошибки, которые здесь зафиксированы тестами: путь на Android 10+ собирался
 * конкатенацией `"$root/$relPath$displayName"` — при `RELATIVE_PATH` без завершающего
 * `/` получался склеенный путь, а при пустом `RELATIVE_PATH` — файл в корне хранилища
 * вместо пропуска записи.
 */
class MediaStoreFilePolicyTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `обычный путь Download`() {
        assertEquals(
            "/storage/emulated/0/Download/photo.apk",
            MediaStoreFilePolicy.scopedPath(root, "Download/", "photo.apk")
        )
    }

    @Test
    fun `RELATIVE_PATH без завершающего слэша не склеивает путь`() {
        assertEquals(
            "/storage/emulated/0/DCIM/photo.apk",
            MediaStoreFilePolicy.scopedPath(root, "DCIM", "photo.apk")
        )
    }

    @Test
    fun `RELATIVE_PATH с ведущим слэшем`() {
        assertEquals(
            "/storage/emulated/0/Pictures/img.jpg",
            MediaStoreFilePolicy.scopedPath(root, "/Pictures/", "img.jpg")
        )
    }

    @Test
    fun `пустые значения означают пропуск записи`() {
        assertNull(MediaStoreFilePolicy.scopedPath(root, null, "a.apk"))
        assertNull(MediaStoreFilePolicy.scopedPath(root, "Download/", null))
        assertNull(MediaStoreFilePolicy.scopedPath(root, "", "a.apk"))
        assertNull(MediaStoreFilePolicy.scopedPath(root, "Download/", "   "))
        assertNull(MediaStoreFilePolicy.scopedPath(root, "   ", "a.apk"))
    }

    @Test
    fun `корень без завершающего слэша не даёт двойного разделителя`() {
        assertEquals(
            "/storage/emulated/0/Download/a.apk",
            MediaStoreFilePolicy.scopedPath("/storage/emulated/0/", "Download/", "a.apk")
        )
    }

    @Test
    fun `расширение APK распознаётся в любом регистре`() {
        assertTrue(MediaStoreFilePolicy.isApk("/sdcard/a.apk"))
        assertTrue(MediaStoreFilePolicy.isApk("/sdcard/a.APK"))
        assertFalse(MediaStoreFilePolicy.isApk("/sdcard/a.jpg"))
        assertFalse(MediaStoreFilePolicy.isApk("/sdcard/apk"))
        assertFalse(MediaStoreFilePolicy.isApk("/sdcard/a.apk.zip"))
    }

    @Test
    fun `APK в Download не находка, вне Download — находка`() {
        assertFalse(MediaStoreFilePolicy.isSuspiciousApk("/storage/emulated/0/Download/app.apk"))
        assertFalse(MediaStoreFilePolicy.isSuspiciousApk("/storage/emulated/0/Downloads/app.apk"))
        assertTrue(MediaStoreFilePolicy.isSuspiciousApk("/storage/emulated/0/DCIM/app.apk"))
        assertTrue(MediaStoreFilePolicy.isSuspiciousApk("/storage/emulated/0/Android/data/x/files/a.apk"))
    }

    @Test
    fun `не-APK не считается подозрительным файлом`() {
        assertFalse(MediaStoreFilePolicy.isSuspiciousApk("/storage/emulated/0/DCIM/photo.jpg"))
    }
}
