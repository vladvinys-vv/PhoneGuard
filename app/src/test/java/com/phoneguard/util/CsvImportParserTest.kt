package com.phoneguard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила импорта CSV: именно они решают, попадёт ли номер в чёрный список.
 *
 * Нормализатор здесь подменён простой функцией (в тестах нет libphonenumber),
 * поэтому проверяется разбор строк, а не сама нормализация E.164.
 */
class CsvImportParserTest {

    /**
     * Убирает всё кроме цифр; буквы означают «не номер».
     *
     * Локальный немецкий формат (`0170 …`) приводится к международному — так же,
     * как это делает libphonenumber в приложении, иначе дубликаты не схлопывались бы.
     */
    private fun normalize(raw: String, minDigits: Int): String? {
        if (raw.any { it.isLetter() }) return null
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("0")) digits = "49" + digits.drop(1)
        if (digits.length !in minDigits..15) return null
        return "+$digits"
    }

    private val strict: (String) -> String? = { normalize(it, 8) }
    private val loose: (String) -> String? = { normalize(it, 4) }

    private fun parse(vararg lines: String): CsvImportParser.Outcome =
        CsvImportParser.parse(lines.asSequence(), strict, loose)

    @Test
    fun `заголовок не считается пропущенной строкой`() {
        val outcome = parse("phone;name", "0170 1234567;Müller")

        assertEquals(listOf("+491701234567"), outcome.numbers)
        assertEquals(2, outcome.totalLines)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `первая строка без номера и без признаков заголовка считается пропуском`() {
        val outcome = parse("какой-то мусор", "0170 1234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
        assertEquals(2, outcome.totalLines)
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `заголовком может быть только первая строка данных`() {
        val outcome = parse("name;phone", "01701234567", "phone;foo")

        assertEquals(1, outcome.numbers.size)
        // Третья строка похожа на заголовок, но она уже не первая — это пропуск.
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `пустые строки и комментарии не считаются данными`() {
        val outcome = parse("# комментарий", "", "    ", "\t", "01701234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
        assertEquals(1, outcome.totalLines)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `дубликаты схлопываются в порядке появления`() {
        val outcome = parse("+49 170 1234567", "01701234567", "0170 1234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
        assertEquals(3, outcome.totalLines)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `написанный номер важнее голой цифровой колонки`() {
        // Первая колонка — id/таймстамп, вторая — настоящий номер.
        val outcome = parse("1700000000;0170 1234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
    }

    @Test
    fun `международный формат важнее локального`() {
        val outcome = parse("0170 9999999;+49 170 1234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
    }

    @Test
    fun `при равном приоритете сохраняется порядок колонок`() {
        val outcome = parse("+491701234567;+491709999999")

        assertEquals(listOf("+491701234567"), outcome.numbers)
    }

    @Test
    fun `поддерживаются все разделители`() {
        val outcome = parse(
            "+491701234561;x",
            "+491701234562,x",
            "+491701234563\tx",
            "+491701234564|x"
        )

        assertEquals(
            listOf("+491701234561", "+491701234562", "+491701234563", "+491701234564"),
            outcome.numbers
        )
        assertEquals(4, outcome.totalLines)
    }

    @Test
    fun `кавычки вокруг поля снимаются`() {
        val outcome = parse("\"Muster, Hans\";\"+49 170 1234567\"")

        assertEquals(listOf("+491701234567"), outcome.numbers)
    }

    @Test
    fun `BOM в начале файла не мешает распознать заголовок`() {
        val outcome = parse("\uFEFFphone;name", "+49 170 1234567")

        assertEquals(listOf("+491701234567"), outcome.numbers)
        assertEquals(2, outcome.totalLines)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `короткий номер проходит только мягкую нормализацию`() {
        val outcome = parse("1234")

        assertEquals(listOf("+1234"), outcome.numbers)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `нераспознанные строки считаются пропущенными`() {
        val outcome = parse("abc", "12")

        assertTrue(outcome.numbers.isEmpty())
        assertEquals(2, outcome.totalLines)
        assertEquals(2, outcome.skipped)
    }

    @Test
    fun `пустой файл даёт пустой результат`() {
        val outcome = parse()

        assertTrue(outcome.numbers.isEmpty())
        assertEquals(0, outcome.totalLines)
        assertEquals(0, outcome.skipped)
    }

    @Test
    fun `кандидаты отбрасывают поля без цифр`() {
        assertEquals(listOf("+491701234567"), CsvImportParser.candidates("Muster;+491701234567"))
        assertEquals(listOf("мусор"), CsvImportParser.candidates("мусор"))
    }

    @Test
    fun `комментарий и BOM распознаются`() {
        assertTrue(CsvImportParser.isIgnorable("   # note"))
        assertTrue(CsvImportParser.isIgnorable("\uFEFF"))
        assertFalse(CsvImportParser.isIgnorable("01701234567"))
        assertEquals("phone", CsvImportParser.stripBom("\uFEFFphone"))
        assertTrue(CsvImportParser.looksLikeHeader("Telefonnummer"))
        assertTrue(CsvImportParser.looksLikeHeader("НОМЕР"))
        assertFalse(CsvImportParser.looksLikeHeader("+491701234567"))
    }
}
