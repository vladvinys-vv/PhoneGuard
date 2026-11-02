package com.phoneguard.intrusion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Информационные действия реакции на вторжение не должны зависеть от настройки
 * «автоответ»: раньше один `return` перед циклом отключал и предупреждение, и запись
 * события в журнал.
 */
class IntrusionResponsePolicyTest {

    private val informational = listOf(
        IntrusionResponsePolicy.Category.WARNING,
        IntrusionResponsePolicy.Category.LOG_EXPORT
    )

    private val active = listOf(
        IntrusionResponsePolicy.Category.LOCK_VAULT,
        IntrusionResponsePolicy.Category.BLOCK_PACKAGE,
        IntrusionResponsePolicy.Category.BLOCK_NETWORK,
        IntrusionResponsePolicy.Category.ALARM,
        IntrusionResponsePolicy.Category.PHOTO,
        IntrusionResponsePolicy.Category.NOTIFY_CONTACTS,
        IntrusionResponsePolicy.Category.HONEYPOT
    )

    @Test
    fun `предупреждение и выгрузка лога выполняются всегда`() {
        informational.forEach { category ->
            assertTrue(category.name, IntrusionResponsePolicy.isInformational(category))
            assertTrue(category.name, IntrusionResponsePolicy.shouldExecute(category, autoResponseEnabled = false))
            assertTrue(category.name, IntrusionResponsePolicy.shouldExecute(category, autoResponseEnabled = true))
        }
    }

    @Test
    fun `активные действия ждут автоответа`() {
        active.forEach { category ->
            assertFalse(category.name, IntrusionResponsePolicy.isInformational(category))
            assertFalse(category.name, IntrusionResponsePolicy.shouldExecute(category, autoResponseEnabled = false))
            assertTrue(category.name, IntrusionResponsePolicy.shouldExecute(category, autoResponseEnabled = true))
        }
    }

    @Test
    fun `набор безусловных действий ровно из двух элементов`() {
        assertTrue(IntrusionResponsePolicy.ALWAYS_ON.size == 2)
        assertTrue(IntrusionResponsePolicy.ALWAYS_ON.containsAll(informational))
    }
}
