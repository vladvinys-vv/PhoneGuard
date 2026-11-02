package com.phoneguard.antitheft

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Матрица «что делать с входящим SMS».
 *
 * Ключевое требование безопасности: без верного PIN удалённая команда (в том числе
 * `WIPE`) не выполняется, а попытка подбора остаётся видимой владельцу — в том числе
 * когда команда в SMS неизвестна.
 */
class SmsCommandGuardTest {

    private fun decide(parsed: Boolean, pin: Boolean, action: Boolean) =
        SmsCommandGuard.decide(commandParsed = parsed, pinValid = pin, actionKnown = action)

    @Test
    fun `обычный текст без команды игнорируется`() {
        assertEquals(SmsCommandGuard.Decision.IGNORE, decide(parsed = false, pin = false, action = false))
    }

    @Test
    fun `неверный PIN отвергается даже при известной команде`() {
        assertEquals(
            SmsCommandGuard.Decision.REJECT_WRONG_PIN,
            decide(parsed = true, pin = false, action = true)
        )
    }

    @Test
    fun `неверный PIN отвергается и при неизвестной команде`() {
        // Иначе подбор PIN по «мусорным» командам проходил бы незамеченным.
        assertEquals(
            SmsCommandGuard.Decision.REJECT_WRONG_PIN,
            decide(parsed = true, pin = false, action = false)
        )
    }

    @Test
    fun `верный PIN и неизвестная команда — ничего не делаем`() {
        assertEquals(
            SmsCommandGuard.Decision.NOTHING_TO_DO,
            decide(parsed = true, pin = true, action = false)
        )
    }

    @Test
    fun `верный PIN и известная команда выполняется`() {
        assertEquals(
            SmsCommandGuard.Decision.EXECUTE,
            decide(parsed = true, pin = true, action = true)
        )
    }

    @Test
    fun `без разобранной команды PIN не проверяется`() {
        // `pinValid = false` при `parsed = false` — именно так вызывающий код передаёт
        // «PIN не проверяли», и это не должно приводить к уведомлению о подборе.
        assertEquals(SmsCommandGuard.Decision.IGNORE, decide(parsed = false, pin = false, action = true))
    }
}
