package com.phoneguard.protect

/**
 * Стоит ли предупреждать о новом «опасном соседе» сразу после установки приложения.
 *
 * Экран «Защита приложений» показывает опасных соседей только когда его откроют, а самое
 * важное время — первые минуты после установки: человек ещё помнит, что ставил, и может
 * сразу отозвать право. Отсюда это правило.
 *
 * Оно намеренно **узкое**. У `PackageAddedReceiver` уже был урок: безусловное «Установлено
 * новое приложение» приучило не читать уведомления, и их пришлось сузить до подписи APK.
 * Поэтому здесь:
 *
 *  * нет защищённых приложений — нет предупреждения: «опасный сосед» без соседа бессмыслен;
 *  * системные приложения и сам PhoneGuard не в счёт (у них права штатные);
 *  * приложение из списка защищённых не считается угрозой само себе;
 *  * `LOW` (только администратор устройства) не будит: это законная функция «Найти устройство»;
 *  * один и тот же пакет — не чаще одного раза в сутки: `ACTION_PACKAGE_ADDED` приходит и при
 *    обновлении, а обновление банка не должно выглядеть как новая угроза.
 *
 * Модуль без Android: правила проверяются JVM-тестами, включая хранение времени последнего
 * уведомления (оно живёт строкой в DataStore — см. [parse], [format], [record]).
 */
object NeighbourAlertPolicy {

    /** Окно, в течение которого повторное уведомление о том же пакете не показывается. */
    const val REPEAT_WINDOW_MS: Long = 24L * 60 * 60 * 1000

    /** Предел числа запомненных пакетов: хранилище не должно расти бесконечно. */
    const val MAX_REMEMBERED = 50

    /** Что показать пользователю. */
    data class Alert(
        val packageName: String,
        val label: String,
        val capabilities: List<SensitiveAppPolicy.Capability>,
        val severity: SensitiveAppPolicy.Severity,
        val protectedCount: Int
    ) {
        /** Пустая подпись не должна давать пустую строку в уведомлении. */
        val displayName: String get() = label.ifBlank { packageName }
    }

    /**
     * Решение по одному только что установленному приложению.
     *
     * @param facts что известно о приложении; `null` — приложение уже удалили или нет доступа
     * @param protected пакеты, которые пользователь отметил как ценные
     * @param lastAlertAt когда об этом пакете уже предупреждали (`null` — не предупреждали)
     * @return `null` — уведомление не нужно
     */
    fun decide(
        facts: SensitiveAppPolicy.AppFacts?,
        protected: Set<String>,
        ownPackageName: String,
        lastAlertAt: Long?,
        nowMillis: Long
    ): Alert? {
        if (facts == null) return null
        if (facts.packageName == ownPackageName) return null
        if (facts.isSystem) return null
        if (facts.packageName in protected) return null
        if (protected.isEmpty()) return null

        val severity = SensitiveAppPolicy.severityOf(facts.capabilities) ?: return null
        if (severity == SensitiveAppPolicy.Severity.LOW) return null

        if (lastAlertAt != null && nowMillis - lastAlertAt < REPEAT_WINDOW_MS) return null

        return Alert(
            packageName = facts.packageName,
            label = facts.label,
            // Порядок объявления — порядок в тексте уведомления: сначала самое опасное право.
            capabilities = SensitiveAppPolicy.Capability.entries.filter { it in facts.capabilities },
            severity = severity,
            protectedCount = protected.size
        )
    }

    /**
     * Разбирает сохранённые отметки и выбрасывает испорченные и просроченные.
     *
     * Формат `пакет=время`: имя пакета не содержит `=`, поэтому разделитель однозначен.
     * Просроченные записи не нужны — окно повтора единственная их функция.
     */
    fun parse(raw: Set<String>, nowMillis: Long): Map<String, Long> {
        val remembered = LinkedHashMap<String, Long>()
        raw.forEach { entry ->
            val separator = entry.lastIndexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return@forEach
            val packageName = entry.substring(0, separator)
            val atMillis = entry.substring(separator + 1).toLongOrNull() ?: return@forEach
            if (atMillis <= 0L) return@forEach
            if (nowMillis - atMillis >= REPEAT_WINDOW_MS) return@forEach
            remembered[packageName] = atMillis
        }
        return remembered.takeNewest()
    }

    /** Обратное к [parse]: набор строк для DataStore. */
    fun format(alerts: Map<String, Long>): Set<String> =
        alerts.entries.map { (packageName, atMillis) -> "$packageName=$atMillis" }.toSet()

    /**
     * Запоминает время уведомления о пакете.
     *
     * Имя не `record`: в проекте уже есть suspend-функция `SimSwapRepository.record`, а
     * `tools/kotlin_analysis.py` сопоставляет вызовы по простому имени — одинаковое имя давало
     * ложные «suspend-вызов в инициализаторе свойства» в тестах этой политики.
     *
     * @return новый набор отметок; прежние записи старше окна и лишние сверх
     *   [MAX_REMEMBERED] выбрасываются — иначе строка в DataStore росла бы с каждой установкой
     */
    fun remember(alerts: Map<String, Long>, packageName: String, atMillis: Long): Map<String, Long> {
        if (packageName.isBlank()) return alerts
        val next = LinkedHashMap(alerts)
        next[packageName] = atMillis
        return next.takeNewest()
    }

    /** Оставляет [MAX_REMEMBERED] самых свежих записей. */
    private fun Map<String, Long>.takeNewest(): Map<String, Long> {
        if (size <= MAX_REMEMBERED) return this
        return entries
            .sortedByDescending { it.value }
            .take(MAX_REMEMBERED)
            .associate { it.key to it.value }
    }
}
