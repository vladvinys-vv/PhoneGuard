package com.phoneguard.util

/**
 * Перенос настроек на новый телефон — файл-обменник и правила «что переносить нельзя».
 *
 * Резервное копирование у приложения выключено осознанно: `dataExtractionRules` исключает и
 * облако, и Device-to-Device, потому что в базе и в сейфе лежат чувствительные данные
 * (снимки нарушителя, хэш PIN, парольная фраза базы). Побочный эффект: при смене телефона
 * пользователь терял **все** настройки — правила блокировки, брандмауэр, доверенный контакт,
 * расписание сканирований — и настраивал заново вручную.
 *
 * Здесь только правила и формат (никакого Android), поэтому они проверяются JVM-тестом:
 *
 *  * [SettingEntry] — типизированная пара «ключ → значение»;
 *  * [NEVER_TRANSFERRED] — то, что не переносится **никогда**: секреты и данные, привязанные
 *    к устройству (хэш PIN, попытки ввода, парольная фраза базы, IMSI, состояние тревоги);
 *  * [encode] / [decode] — текстовый формат «тип, ключ, значение», который можно прочитать
 *    глазами и приложить к письму;
 *  * [planImport] — что будет применено, а что отклонено и почему.
 *
 * Формат:
 * ```
 * # PhoneGuard settings
 * format=phoneguard-settings
 * version=1
 * boolean	block_unknown	true
 * string	backup_number	+4915112345678
 * ```
 */
object SettingsTransfer {

    /** Версия формата: старые файлы читаются, новые без понимания версии — отклоняются. */
    const val VERSION = 1

    const val FORMAT_ID = "phoneguard-settings"

    /** Тип значения. */
    enum class Type { BOOLEAN, INT, LONG, STRING }

    data class SettingEntry(val key: String, val type: Type, val value: String)

    /**
     * Разобранный файл.
     *
     * @param skippedLines строки, которые не удалось разобрать (нет разделителей, неизвестный
     *   тип). Они не отменяют импорт: остальные настройки применяются.
     */
    data class Bundle(
        val version: Int,
        val entries: List<SettingEntry>,
        val skippedLines: Int = 0
    )

    /** Почему запись не будет применена. */
    enum class RejectionReason {
        /** Секрет или данные, привязанные к устройству. */
        NOT_TRANSFERABLE,

        /** Значение не соответствует типу (`boolean` со значением `maybe`). */
        INVALID_VALUE
    }

    data class Rejection(val key: String, val reason: RejectionReason)

    data class ImportPlan(val accepted: List<SettingEntry>, val rejected: List<Rejection>) {
        val nothingToImport: Boolean get() = accepted.isEmpty()
    }

    /**
     * Ключи, которые не переносятся ни при каких условиях.
     *
     * Причина у каждого своя:
     *  * `pin_code_hash`, `pin_failed_attempts`, `pin_locked_until`, `pin_lockout_*` — секрет
     *    и состояние блокировки: перенос хэша PIN означал бы, что файл настроек содержит
     *    учётные данные (и что PIN можно подобрать офлайн по чужому файлу);
     *  * `database_passphrase` / `db_passphrase` — парольная фраза зашифрованной базы. Даже
     *    если она хранится в Keystore, привязанном к устройству, переносить её через файл
     *    нельзя: файл уходит из песочницы приложения;
     *  * `last_known_imsi` — привязан к SIM и к старому устройству, на новом это ложное
     *    событие «SIM подменили»;
     *  * `lost_mode_active` — состояние «телефон украден»: на новом телефоне оно не имеет
     *    смысла и включало бы сирену сразу после импорта;
     *  * `is_pro` — покупка восстанавливается через магазин, а не файлом (иначе файлом можно
     *    «включить» платную функцию);
     *  * `device_audit_*`, `last_scan_time` — результаты проверки конкретного устройства.
     */
    val NEVER_TRANSFERRED: Set<String> = setOf(
        "pin_code_hash",
        "pin_failed_attempts",
        "pin_locked_until",
        "pin_lockout_remaining_ms",
        "pin_lockout_elapsed_mark",
        "database_passphrase",
        "db_passphrase",
        "last_known_imsi",
        "lost_mode_active",
        "remote_alarm_enabled",
        "is_pro",
        "device_audit_score",
        "device_audit_issues",
        "device_audit_time",
        "last_scan_time"
    )

    /** Можно ли переносить ключ. */
    fun isTransferable(key: String): Boolean = key.isNotBlank() && key !in NEVER_TRANSFERRED

    /**
     * Сериализует настройки. Порядок записей сохраняется (его задаёт вызывающий код), чтобы
     * файл был стабильным и его можно было сравнивать между версиями.
     */
    fun encode(entries: List<SettingEntry>): String = buildString {
        appendLine("# PhoneGuard settings")
        appendLine("# Секреты и данные, привязанные к устройству, не переносятся (см. SettingsTransfer).")
        appendLine("format=$FORMAT_ID")
        appendLine("version=$VERSION")
        entries.filter { isTransferable(it.key) }.forEach { entry ->
            append(entry.type.name.lowercase())
            append('\t')
            append(entry.key)
            append('\t')
            appendLine(escape(entry.value))
        }
    }

    /**
     * Разбирает файл настроек.
     *
     * Ошибка возвращается только для структурных проблем (нет заголовка, чужая версия):
     * отдельные испорченные строки не должны мешать применить остальные настройки — они
     * попадут в [ImportPlan.rejected].
     */
    fun decode(text: String): Result<Bundle> {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
        var format: String? = null
        var version: Int? = null
        var skippedLines = 0
        val entries = mutableListOf<SettingEntry>()

        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            when {
                line.startsWith("format=") -> format = line.removePrefix("format=").trim()
                line.startsWith("version=") -> {
                    version = line.removePrefix("version=").trim().toIntOrNull()
                }
                else -> {
                    val parsed = parseEntry(line)
                    if (parsed == null) skippedLines++ else entries += parsed
                }
            }
        }

        if (format != FORMAT_ID) {
            return Result.failure(IllegalArgumentException("чужой формат файла: ${format ?: "нет"}",))
        }
        val fileVersion = version ?: return Result.failure(
            IllegalArgumentException("в файле нет версии формата")
        )
        if (fileVersion > VERSION) {
            return Result.failure(
                IllegalArgumentException(
                    "файл новее приложения (версия $fileVersion > $VERSION) — обновите приложение"
                )
            )
        }
        return Result.success(Bundle(fileVersion, entries, skippedLines))
    }

    /**
     * Что будет применено при импорте.
     *
     * Повторяющиеся ключи: побеждает последняя запись (файл мог быть поправлен вручную),
     * но в применённый список ключ попадает один раз.
     */
    fun planImport(bundle: Bundle): ImportPlan {
        val accepted = LinkedHashMap<String, SettingEntry>()
        val rejected = mutableListOf<Rejection>()
        bundle.entries.forEach { entry ->
            if (!isTransferable(entry.key)) {
                rejected += Rejection(entry.key, RejectionReason.NOT_TRANSFERABLE)
                return@forEach
            }
            if (!valueMatchesType(entry)) {
                rejected += Rejection(entry.key, RejectionReason.INVALID_VALUE)
                return@forEach
            }
            accepted.remove(entry.key)
            accepted[entry.key] = entry
        }
        return ImportPlan(accepted.values.toList(), rejected)
    }

    /** Проверка значения против типа (нужна и при импорте, и при экспорте). */
    fun valueMatchesType(entry: SettingEntry): Boolean = when (entry.type) {
        Type.BOOLEAN -> entry.value == "true" || entry.value == "false"
        Type.INT -> entry.value.toIntOrNull() != null
        Type.LONG -> entry.value.toLongOrNull() != null
        Type.STRING -> true
    }

    private fun parseEntry(line: String): SettingEntry? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val type = when (parts[0].trim().lowercase()) {
            "boolean" -> Type.BOOLEAN
            "int" -> Type.INT
            "long" -> Type.LONG
            "string" -> Type.STRING
            else -> null
        } ?: return null
        val key = parts[1].trim()
        if (key.isEmpty()) return null
        // Значение может содержать табуляцию — её экранирует escape(), поэтому склеиваем хвост.
        val value = unescape(parts.drop(2).joinToString("\t"))
        return SettingEntry(key, type, value)
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    '\\' -> { append('\\'); index += 2 }
                    't' -> { append('\t'); index += 2 }
                    'n' -> { append('\n'); index += 2 }
                    'r' -> { append('\r'); index += 2 }
                    else -> { append(char); index++ }
                }
            } else {
                append(char)
                index++
            }
        }
    }
}
