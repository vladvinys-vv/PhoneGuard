package com.phoneguard.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Общее состояние «сейф разблокирован».
 *
 * Тест был только в `VaultViewModelTest`, который офлайн не выполняется (MockK): сама
 * сессия — чистый класс, и её поведение можно проверить напрямую. Именно от этого флага
 * зависит, закрывает ли реакция на вторжение сейф, или гасит экран всего устройства.
 */
class VaultSessionTest {

    @Test
    fun `vault starts locked`() {
        assertFalse(VaultSession().unlocked.value)
    }

    @Test
    fun `unlock and lock flip the flag`() {
        val session = VaultSession()
        session.unlock()
        assertTrue(session.unlocked.value)
        session.lock()
        assertFalse(session.unlocked.value)
    }

    @Test
    fun `repeated unlock and lock are idempotent`() {
        val session = VaultSession()
        session.unlock()
        session.unlock()
        assertTrue(session.unlocked.value)
        session.lock()
        session.lock()
        assertFalse(session.unlocked.value)
    }

    @Test
    fun `all collectors see the same state`() {
        // Состояние одно на приложение: экран сейфа и реакция на вторжение читают его из
        // разных мест, и «своя копия флага» в каждом из них — это и был прежний баг.
        val session = VaultSession()
        val first = session.unlocked
        val second = session.unlocked
        session.unlock()
        assertTrue(first.value)
        assertTrue(second.value)

        val other = VaultSession()
        session.lock()
        assertFalse(first.value)
        assertFalse("второй экземпляр не должен видеть чужой флаг", other.unlocked.value)
    }
}
