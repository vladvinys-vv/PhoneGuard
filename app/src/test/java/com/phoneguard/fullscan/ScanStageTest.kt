package com.phoneguard.fullscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этапы полного сканирования.
 *
 * Раньше текст этапа был зашит по-русски в оркестраторе, и прогресс не переводился. Теперь
 * этап — enum со ссылкой на строковый ресурс, и у каждого этапа ресурс должен быть свой:
 * скопированная ссылка на ресурс показывала бы пользователю чужой этап, а заметить это
 * можно только на живом сканировании.
 */
class ScanStageTest {

    @Test
    fun `every stage has its own string resource`() {
        val duplicates = ScanStage.entries
            .groupBy { it.titleRes }
            .filterValues { it.size > 1 }
        assertTrue("этапы с одинаковым текстом: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every stage points at a real resource`() {
        ScanStage.entries.forEach { stage ->
            assertTrue("у этапа $stage нет строкового ресурса", stage.titleRes != 0)
        }
    }

    @Test
    fun `scan reports both completion and a refused start`() {
        // Эти два состояния появились позже остальных: без них экран либо висел на последнем
        // этапе, либо не объяснял, почему второе сканирование не началось.
        assertTrue(ScanStage.entries.contains(ScanStage.FINISHED))
        assertTrue(ScanStage.entries.contains(ScanStage.ALREADY_RUNNING))
        assertEquals(
            "FINISHED и ALREADY_RUNNING — разные сообщения",
            false,
            ScanStage.FINISHED.titleRes == ScanStage.ALREADY_RUNNING.titleRes
        )
    }
}
