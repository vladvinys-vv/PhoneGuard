package com.phoneguard.protect

/**
 * Кого защищать и от кого.
 *
 * «Защита чувствительных приложений» до этого существовала только как находки сканера:
 * отдельные проверки умели находить приложения с правом рисовать поверх других окон или с
 * включённой службой доступности, но связать это с **банком пользователя** было некому.
 * Пользователь видел список подозрительных приложений и не понимал, чем он рискует.
 *
 * Здесь правила, которые превращают два независимых списка в один осмысленный ответ:
 * «эти приложения важны» и «эти приложения могут подсматривать». Android не даёт обычному
 * приложению запретить чужому рисовать поверх банка — оверлей-атака обходится без root
 * только со стороны системы, — поэтому честный продукт здесь не «блокировка», а
 * **предупреждение с конкретным адресатом**: кто, что умеет и что с этим делать.
 *
 * Особенности, заложенные в правила:
 *
 *  * системные приложения в список «опасных соседей» не попадают: право поверх окон у них
 *    штатное (то же меню громкости), и предупреждение о них обесценило бы все остальные;
 *  * защищённое приложение само не считается угрозой, даже если у него есть оверлей;
 *  * важность зависит от комбинации прав, а не от одного: возможность рисовать поверх окон
 *    сама по себе безобидна (пузырьки мессенджеров), а вместе с доступом к уведомлениям или
 *    службой доступности — это уже перехват ввода;
 *  * подсказки — эвристика по имени и пакету, поэтому это именно «можно добавить», а не
 *    «уже защищено»: решение всегда за пользователем.
 *
 * Модуль без Android: правила проверяются JVM-тестами.
 */
object SensitiveAppPolicy {

    /** Что приложение умеет делать с чужим экраном, вводом и уведомлениями. */
    enum class Capability {
        /** `SYSTEM_ALERT_WINDOW`: рисовать поверх других приложений (tapjacking). */
        OVERLAY,

        /** Включённая служба доступности: читает содержимое экрана. */
        ACCESSIBILITY,

        /** Активный администратор устройства: может блокировать и стирать. */
        DEVICE_ADMIN,

        /** Доступ к уведомлениям: читает коды из банковских SMS и пушей. */
        NOTIFICATION_ACCESS
    }

    /** Насколько опасно сочетание прав рядом с ценным приложением. */
    enum class Severity { LOW, MEDIUM, HIGH }

    /**
     * Что известно о приложении.
     *
     * @param label подпись для интерфейса (может быть пустой — тогда показываем пакет)
     */
    class AppFacts(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val capabilities: Set<Capability> = emptySet()
    ) {
        /** Подпись для интерфейса: пустое имя не должно давать пустую строку в списке. */
        val displayName: String get() = label.ifBlank { packageName }
    }

    /** Опасное соседство: приложение с правами рядом с защищёнными. */
    class Exposure(
        val packageName: String,
        val label: String,
        val capabilities: List<Capability>,
        val severity: Severity
    ) {
        val displayName: String get() = label.ifBlank { packageName }
    }

    /** Предел числа защищённых приложений: список должен оставаться осмысленным. */
    const val MAX_PROTECTED = 25

    /**
     * Слова, по которым приложение выглядит ценным: банки, мессенджеры, почта, менеджеры
     * паролей, криптокошельки. Сравнение идёт по подписи и по имени пакета в нижнем регистре.
     */
    private val SENSITIVE_MARKERS = listOf(
        "bank", "sparkasse", "volksbank", "commerzbank", "postbank", "targobank", "dkb",
        "n26", "revolut", "comdirect", "ing-diba", "hypovereinsbank",
        "whatsapp", "telegram", "signal", "threema", "viber", "messenger", "discord",
        "gmail", "outlook", "protonmail", "tuta", "mail",
        "1password", "bitwarden", "keepass", "dashlane", "lastpass", "enpass", "password",
        "wallet", "binance", "coinbase", "kraken", "bitpanda", "trade-republic"
    )

    /** Выглядит ли приложение ценным для пользователя. */
    fun looksSensitive(packageName: String, label: String): Boolean {
        if (packageName.isBlank()) return false
        val haystack = "${label.lowercase()} ${packageName.lowercase()}"
        return SENSITIVE_MARKERS.any { it in haystack }
    }

    /**
     * Важность по набору прав.
     *
     * @return `null` — прав нет, приложение не опасно
     */
    fun severityOf(capabilities: Set<Capability>): Severity? = when {
        Capability.OVERLAY in capabilities &&
            (Capability.ACCESSIBILITY in capabilities ||
                Capability.NOTIFICATION_ACCESS in capabilities) -> Severity.HIGH
        // Доступ к уведомлениям без оверлея — это чтение кодов из SMS и пушей: уже опасно,
        // но без подмены экрана.
        Capability.NOTIFICATION_ACCESS in capabilities -> Severity.MEDIUM
        Capability.ACCESSIBILITY in capabilities -> Severity.MEDIUM
        Capability.OVERLAY in capabilities -> Severity.MEDIUM
        Capability.DEVICE_ADMIN in capabilities -> Severity.LOW
        else -> null
    }

    /**
     * Кто может подсматривать рядом с защищёнными приложениями.
     *
     * @param protected пакеты, которые пользователь отметил как ценные
     */
    fun exposures(protected: Set<String>, apps: List<AppFacts>): List<Exposure> = apps
        .asSequence()
        .filter { !it.isSystem }
        .filter { it.packageName !in protected }
        .filter { it.capabilities.isNotEmpty() }
        .mapNotNull { app ->
            val severity = severityOf(app.capabilities) ?: return@mapNotNull null
            Exposure(
                packageName = app.packageName,
                label = app.displayName,
                // Порядок объявления — порядок в интерфейсе: сначала самое опасное право.
                capabilities = Capability.entries.filter { it in app.capabilities },
                severity = severity
            )
        }
        .sortedWith(compareByDescending<Exposure> { it.severity.ordinal }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.packageName })
        .toList()

    /**
     * Что предложить пользователю отметить.
     *
     * Системные приложения и уже защищённые не предлагаются; сортировка по подписи, чтобы
     * список не прыгал между открытиями экрана.
     */
    fun suggestions(apps: List<AppFacts>, protected: Set<String>): List<AppFacts> = apps
        .asSequence()
        .filter { !it.isSystem }
        .filter { it.packageName !in protected }
        .filter { looksSensitive(it.packageName, it.label) }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.packageName }))
        .toList()

    /**
     * Защищённые приложения с подписями.
     *
     * Пакет, которого больше нет на телефоне, остаётся в списке с именем пакета: молча
     * выкидывать его нельзя — иначе после переустановки приложения защита «потеряется»
     * без единого слова.
     */
    fun protectedApps(protected: Set<String>, apps: List<AppFacts>): List<AppFacts> {
        val known = apps.associateBy { it.packageName }
        return protected
            .sortedWith(compareBy({ it.lowercase() }))
            .map { packageName ->
                known[packageName] ?: AppFacts(packageName, packageName, isSystem = false)
            }
    }

    /**
     * Новый набор защищённых приложений после переключения одного.
     *
     * @return тот же набор, если переключать нечего или достигнут [MAX_PROTECTED]
     */
    fun toggle(current: Set<String>, packageName: String, isProtected: Boolean): Set<String> {
        if (packageName.isBlank()) return current
        return when {
            !isProtected -> current - packageName
            packageName in current -> current
            current.size >= MAX_PROTECTED -> current
            else -> current + packageName
        }
    }

    /** Достигнут ли предел списка (интерфейс показывает это вместо тихого отказа). */
    fun isAtLimit(current: Set<String>): Boolean = current.size >= MAX_PROTECTED
}
