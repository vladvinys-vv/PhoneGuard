package com.phoneguard.callblocker

/**
 * Порядок правил блокировки входящего звонка.
 *
 * Раньше вся цепочка (`белый список → чёрный список → спам-база → скрытый номер →
 * неразобранный номер → международный`) была выписана прямо в
 * `CallScreeningServiceImpl.checkShouldBlockNumber`, в файле сервиса с `Context`,
 * Room и DataStore. Порядок здесь — это и есть политика: если белый список
 * перестанет быть первым, пользователь начнёт терять звонки, которые сам разрешил.
 * Поэтому правила вынесены в чистую функцию с JVM-тестами, а сервис только
 * собирает факты (и делает это с короткими замыканиями, чтобы не читать БД зря).
 *
 * Факты по умолчанию — `false`: не прочитанная настройка не блокирует звонок.
 */
internal object CallBlockDecision {

    /**
     * Факты о конкретном звонке.
     *
     * @param whitelisted номер есть в белом списке пользователя
     * @param blacklisted номер есть в чёрном списке
     * @param spamDatabase номер найден в локальной спам-базе
     * @param callerIdHidden номер скрыт или не передан (`handle == null`)
     * @param numberParsed номер удалось привести к E.164
     * @param blockUnknown включена настройка «блокировать неизвестные номера»
     * @param blockHidden включена настройка «блокировать скрытые номера»
     * @param blockInternational включена настройка «блокировать международные звонки»
     * @param foreignNumber код страны номера не совпадает с регионом устройства
     */
    data class Facts(
        val whitelisted: Boolean = false,
        val blacklisted: Boolean = false,
        val spamDatabase: Boolean = false,
        val callerIdHidden: Boolean = false,
        val numberParsed: Boolean = false,
        val blockUnknown: Boolean = false,
        val blockHidden: Boolean = false,
        val blockInternational: Boolean = false,
        val foreignNumber: Boolean = false
    )

    /** @return `true`, если звонок нужно отклонить */
    fun shouldBlock(facts: Facts): Boolean = when {
        // 1. Белый список важнее любых правил: его выставляет сам пользователь.
        facts.whitelisted -> false
        // 2. Чёрный список и спам-база блокируют независимо от настроек.
        facts.blacklisted -> true
        facts.spamDatabase -> true
        // 3. Скрытый номер: номер не определить, поэтому решают две настройки сразу.
        facts.callerIdHidden -> facts.blockHidden || facts.blockUnknown
        // 4. Номер есть, но не разобрался (буквенный sender id, мусор).
        !facts.numberParsed -> facts.blockUnknown
        // 5. Международный звонок.
        facts.blockInternational && facts.foreignNumber -> true
        else -> false
    }
}
