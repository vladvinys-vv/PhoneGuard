package com.phoneguard.antitheft

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор удалённых команд `#PG:PIN:COMMAND`.
 *
 * От него зависит, выполнится ли удалённая блокировка, тревога или стирание данных. Разбор живёт
 * в чистом объекте (раньше был зашит в `BroadcastReceiver` и не исполнялся ни одним тестом).
 */
class SmsRemoteCommandTest {

    @Test
    fun `parses pin and known action`() {
        val command = SmsRemoteCommand.parse("#PG:1234:LOCK")!!

        assertEquals("1234", command.pin)
        assertEquals(SmsRemoteCommand.Action.LOCK, command.action)
    }

    @Test
    fun `action is case insensitive`() {
        assertEquals(SmsRemoteCommand.Action.WIPE, SmsRemoteCommand.parse("#PG:1234:wipe")!!.action)
        assertEquals(SmsRemoteCommand.Action.ALARM, SmsRemoteCommand.parse("#PG:9999:AlArM")!!.action)
        assertEquals(SmsRemoteCommand.Action.STATUS, SmsRemoteCommand.parse("#pg:1:status")!!.action)
    }

    @Test
    fun `turkish default locale does not break the action`() {
        // Регресс-страховка: `uppercase()` без локали в турецкой локали превращает «i» в «İ»,
        // и «wipe» перестаёт совпадать с «WIPE» — команда стирания молча не срабатывала бы.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val command = SmsRemoteCommand.parse("#PG:1234:wipe")
            assertEquals(SmsRemoteCommand.Action.WIPE, command!!.action)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `unknown action keeps pin but is not executed`() {
        val command = SmsRemoteCommand.parse("#PG:1234:SELFDESTRUCT")!!

        assertEquals("1234", command.pin)
        assertNull("неизвестная команда не должна отображаться на действие", command.action)
        assertTrue(SmsRemoteCommand.containsCommand("#PG:1234:SELFDESTRUCT"))
    }

    @Test
    fun `text without command is not parsed`() {
        assertNull(SmsRemoteCommand.parse("Привет, как дела?"))
        assertNull(SmsRemoteCommand.parse(""))
        assertNull(SmsRemoteCommand.parse("#PG:LOCK"))
        assertFalse(SmsRemoteCommand.containsCommand("перешли 1000 рублей"))
    }

    @Test
    fun `pin must be digits`() {
        assertNull(SmsRemoteCommand.parse("#PG:abcd:LOCK"))
        assertNull(SmsRemoteCommand.parse("#PG::LOCK"))
    }

    @Test
    fun `command inside longer text is found`() {
        // Пользователь пересылает инструкцию целиком — команда всё равно распознаётся.
        val command = SmsRemoteCommand.parse("Инструкция: отправьте #PG:4321:PHOTO на этот номер")

        assertEquals("4321", command!!.pin)
        assertEquals(SmsRemoteCommand.Action.PHOTO, command.action)
    }

    @Test
    fun `first command wins when several are present`() {
        val command = SmsRemoteCommand.parse("#PG:1111:LOCK и ещё #PG:2222:WIPE")

        assertEquals("1111", command!!.pin)
        assertEquals(SmsRemoteCommand.Action.LOCK, command.action)
    }

    @Test
    fun `multipart sms joined is parsed`() {
        // Многочастное SMS приходит несколькими частями; ресивер склеивает их до разбора.
        val joined = "#PG:12" + "34:LOC" + "ATION"
        val command = SmsRemoteCommand.parse(joined)

        assertEquals("1234", command!!.pin)
        assertEquals(SmsRemoteCommand.Action.LOCATION, command.action)
    }

    @Test
    fun `trailing words after the action do not break parsing`() {
        assertEquals(SmsRemoteCommand.Action.ALARM, SmsRemoteCommand.parse("#PG:1234:ALARM please")!!.action)
        assertEquals(SmsRemoteCommand.Action.LOCK, SmsRemoteCommand.parse("#PG:1234:LOCK now!")!!.action)
    }

    @Test
    fun `spaces inside the command break parsing`() {
        // Строгий формат: иначе «#PG: 1234: LOCK» из чужого шаблона могло бы сработать случайно.
        assertNull(SmsRemoteCommand.parse("#PG: 1234:LOCK"))
        assertNull(SmsRemoteCommand.parse("#PG:1234: LOCK"))
    }

    @Test
    fun `all known actions are reachable`() {
        val expected = SmsRemoteCommand.Action.entries.associateWith { action ->
            SmsRemoteCommand.parse("#PG:1234:${action.name}")?.action
        }
        assertEquals(SmsRemoteCommand.Action.entries.toSet(), expected.keys)
        expected.forEach { (action, parsed) -> assertEquals(action, parsed) }
    }
}
