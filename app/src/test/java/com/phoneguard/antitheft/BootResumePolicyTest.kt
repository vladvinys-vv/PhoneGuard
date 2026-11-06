package com.phoneguard.antitheft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * После каких broadcast'ов тревога поднимается заново.
 *
 * Ошибка в этом правиле не видна ни компилятору, ни устройству до самого события: забытое
 * `MY_PACKAGE_REPLACED` означает, что после обновления приложения сирена молчит, а
 * `BOOT_COMPLETED` — что режим потери не восстанавливается после перезагрузки. Обратная
 * ошибка — реакция на любое действие — включает сирену без причины.
 */
class BootResumePolicyTest {

    @Test
    fun `загрузка устройства возобновляет тревогу`() {
        assertTrue(BootResumePolicy.shouldResume("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `обновление приложения возобновляет тревогу`() {
        assertTrue(BootResumePolicy.shouldResume("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun `посторонние действия тревогу не трогают`() {
        assertFalse(BootResumePolicy.shouldResume("android.intent.action.SCREEN_ON"))
        assertFalse(BootResumePolicy.shouldResume("android.intent.action.TIME_SET"))
    }

    @Test
    fun `действие без значения не считается загрузкой`() {
        assertFalse(BootResumePolicy.shouldResume(null))
        assertFalse(BootResumePolicy.shouldResume(""))
    }

    @Test
    fun `сравнение точное, а не по подстроке`() {
        assertFalse(BootResumePolicy.shouldResume("android.intent.action.BOOT_COMPLETED_EXTRA"))
    }
}
