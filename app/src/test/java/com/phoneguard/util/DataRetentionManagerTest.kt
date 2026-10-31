package com.phoneguard.util

import android.content.Context
import com.phoneguard.data.local.AppDatabase
import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.data.local.IntrusionEventEntity
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Уборка устаревших данных: 90 дней для журналов **и** улик вторжений.
 *
 * Отдельно проверяется, что файл вне приватных каталогов приложения не удаляется:
 * `photoPath` приходит из БД, и удалять по нему произвольный путь нельзя.
 */
class DataRetentionManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val intrusionEventDao: IntrusionEventDao = mockk(relaxed = true)

    private lateinit var filesRoot: File
    private lateinit var manager: DataRetentionManager

    @Before
    fun setup() {
        filesRoot = createTempDir(prefix = "phoneguard-files")
        every { context.filesDir } returns filesRoot
        every { context.cacheDir } returns filesRoot
        every { context.getExternalFilesDir(null) } returns null
        every { database.intrusionEventDao() } returns intrusionEventDao
        manager = DataRetentionManager(context, database)
    }

    @After
    fun tearDown() {
        filesRoot.deleteRecursively()
    }

    @Test
    fun `deletes evidence photo stored inside app files`() = runTest {
        val photo = File(filesRoot, "intrusion.jpg").apply { writeText("evidence") }
        coEvery { intrusionEventDao.getOlderThan(any()) } returns listOf(eventWithPhoto(photo))

        manager.cleanupOldLogs()

        assertFalse("файл-улика старше 90 дней должен удаляться", photo.exists())
        coVerify(exactly = 1) { intrusionEventDao.deleteOlderThan(any()) }
    }

    @Test
    fun `keeps file outside app storage`() = runTest {
        val outside = createTempDir(prefix = "phoneguard-outside")
        val foreign = File(outside, "someone-elses.jpg").apply { writeText("keep me") }
        coEvery { intrusionEventDao.getOlderThan(any()) } returns listOf(eventWithPhoto(foreign))

        manager.cleanupOldLogs()

        assertTrue("пути вне приватных каталогов приложения удалять нельзя", foreign.exists())
        outside.deleteRecursively()
    }

    @Test
    fun `does not touch database when there are no old events`() = runTest {
        coEvery { intrusionEventDao.getOlderThan(any()) } returns emptyList()

        manager.cleanupOldLogs()

        coVerify(exactly = 0) { intrusionEventDao.deleteOlderThan(any()) }
    }

    private fun eventWithPhoto(file: File) = IntrusionEventEntity(
        id = 1,
        timestamp = System.currentTimeMillis() - 120L * 24 * 60 * 60 * 1000,
        type = IntrusionType.UNKNOWN,
        severity = IntrusionSeverity.HIGH,
        photoPath = file.absolutePath
    )
}
