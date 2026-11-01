package com.phoneguard.fullscan.checks

import com.phoneguard.fullscan.checks.FileSystemHeuristics.Entry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила файловой проверки. Главный регресс — ложные находки в `.nomedia`-каталогах:
 * у подпапок на Android всегда выставлен бит выполнения, и прежняя проверка
 * `it.canExecute()` объявляла их «скрытым исполняемым содержимым».
 */
class FileSystemHeuristicsTest {

    private fun file(name: String, canExecute: Boolean = false) =
        Entry(name = name, isDirectory = false, canExecute = canExecute)

    private fun dir(name: String) = Entry(name = name, isDirectory = true, canExecute = true)

    @Test
    fun emptyDirectoryIsNotSuspicious() {
        assertFalse(FileSystemHeuristics.containsSuspiciousEntries(emptyList()))
    }

    @Test
    fun ordinaryMediaIsNotSuspicious() {
        assertFalse(
            FileSystemHeuristics.containsSuspiciousEntries(
                listOf(file("IMG_0001.jpg"), file("voice.opus"), file("notes.txt"))
            )
        )
    }

    @Test
    fun subdirectoryWithExecuteBitIsNotSuspicious() {
        // Именно этот случай давал ложные находки в кэше мессенджеров.
        assertFalse(
            FileSystemHeuristics.containsSuspiciousEntries(
                listOf(dir("sent"), dir("Media"), file("photo.jpg"))
            )
        )
    }

    @Test
    fun executableScriptIsSuspicious() {
        assertTrue(
            FileSystemHeuristics.containsSuspiciousEntries(listOf(file("payload.sh", canExecute = true)))
        )
    }

    @Test
    fun suspiciousExtensionIsSuspiciousEvenWithoutExecuteBit() {
        assertTrue(FileSystemHeuristics.containsSuspiciousEntries(listOf(file("update.apk"))))
        assertTrue(FileSystemHeuristics.containsSuspiciousEntries(listOf(file("classes.dex"))))
        assertTrue(FileSystemHeuristics.containsSuspiciousEntries(listOf(file("script.PS1"))))
    }

    @Test
    fun executableFileWithoutSuspiciousExtensionIsSuspicious() {
        assertTrue(
            FileSystemHeuristics.containsSuspiciousEntries(
                listOf(file("dropper", canExecute = true))
            )
        )
    }

    @Test
    fun nameWithoutExtensionIsNotSuspiciousUnlessExecutable() {
        assertFalse(FileSystemHeuristics.containsSuspiciousEntries(listOf(file("README"))))
    }

    @Test
    fun downloadsDirectoryIsDetected() {
        assertTrue(
            FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/Download/app.apk")
        )
        assertTrue(
            FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/DOWNLOADS/app.apk")
        )
        assertTrue(
            FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/Download/nested/app.apk")
        )
    }

    @Test
    fun otherDirectoriesAreNotDownloads() {
        assertFalse(FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/DCIM/app.apk"))
        assertFalse(FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/Downloaded/app.apk"))
        assertFalse(FileSystemHeuristics.isInsideDownloads("/storage/emulated/0/Android/data/x/app.apk"))
    }

    @Test
    fun backslashesAndCaseAreNormalised() {
        assertTrue(FileSystemHeuristics.isInsideDownloads("C:\\Users\\x\\Download\\app.apk"))
    }
}
