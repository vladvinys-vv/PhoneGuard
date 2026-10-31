package com.phoneguard.intrusion

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Приманки honeypot: имена, содержимое и отсутствие «мусорного» роста каталога.
 */
class HoneypotManagerTest {

    private val manager = HoneypotManager()
    private val context: Context = mockk(relaxed = true)
    private lateinit var filesDir: File

    private fun event(timestamp: Long) = IntrusionEvent(
        type = IntrusionType.RAT_DETECTED,
        severity = IntrusionSeverity.HIGH,
        timestamp = timestamp
    )

    @Before
    fun setup() {
        filesDir = createTempDir(prefix = "phoneguard-honeypot")
        every { context.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun decoyNamesContainEventTimestamp() {
        val names = manager.decoyNames(event(42L))
        assertEquals(3, names.size)
        assertTrue(names.all { it.startsWith("42_") })
    }

    @Test
    fun decoyAccessIsDetectedOnlyForRealDecoyNames() {
        assertTrue(manager.isDecoyAccess("/data/user/0/com.phoneguard/files/phoneguard_honeypot/42_private_photos"))
        assertTrue(manager.isDecoyAccess("/data/user/0/com.phoneguard/files/phoneguard_honeypot/42_passwords.txt"))

        // Похожие имена не должны считаться обращением к приманке.
        assertFalse(manager.isDecoyAccess("/sdcard/Download/mypasswords.txt.bak"))
        assertFalse(manager.isDecoyAccess("/data/user/0/com.phoneguard/files/private_photos"))
        assertFalse(manager.isDecoyAccess("/sdcard/DCIM/42_passwords.txt.bak"))
    }

    @Test
    fun decoyContentLooksLikeRealFiles() {
        val decoys = manager.createDecoys(context, event(1_700_000_000_000L))
        val passwords = decoys.first { it.name.endsWith("passwords.txt") }
        val wallet = decoys.first { it.name.endsWith("wallet_backup.json") }

        val passwordsText = passwords.readText()
        assertTrue("приманка не должна выдавать себя служебным текстом", "PhoneGuard" !in passwordsText)
        assertTrue(passwordsText.contains("password:"))
        assertTrue(wallet.readText().contains("mnemonic"))
    }

    @Test
    fun oldDecoySetsArePruned() {
        (1..5).forEach { index ->
            manager.createDecoys(context, event(1_000L * index))
        }
        val dir = File(filesDir, HoneypotManager.DIR_NAME)
        val sets = dir.listFiles().orEmpty().map { it.name.substringBefore('_') }.distinct()

        assertEquals("должно остаться не больше 3 наборов приманок", 3, sets.size)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("5000_") })
        assertFalse(dir.listFiles().orEmpty().any { it.name.startsWith("1000_") })
    }
}
