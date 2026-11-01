package com.phoneguard.model

/**
 * Скоринг риска spyware: уровень приложения по его признакам и общий балл по устройству.
 *
 * Вынесено из `SpywareCheckRepository` (там рядом DAO, `PackageManager` и корутины): именно эти
 * правила решают, покажет ли приложение «критический» риск или отправит находку в общий список.
 *
 * Правила (порядок важен — проверяются сверху вниз):
 *  1. Device Admin **и** служба доступности — [RiskLevel.CRITICAL]: связка «управление устройством
 *     + чтение экрана» это и есть профиль банковского трояна;
 *  2. что-то одно из них, либо APK изменён после установки, либо debug-подпись у
 *     sideload-приложения — [RiskLevel.HIGH];
 *  3. любые два признака — [RiskLevel.HIGH];
 *  4. один признак — [RiskLevel.MEDIUM];
 *  5. ничего — [RiskLevel.LOW].
 *
 * Признаки сравниваются как множество: дубликат одного и того же индикатора не должен
 * «дотягивать» приложение до HIGH.
 */
object SpywareRiskScoring {

    /** Вес уровня в общем балле: сумма по приложениям обрезается до 0..100 на экране. */
    fun weight(level: RiskLevel): Int = when (level) {
        RiskLevel.LOW -> 10
        RiskLevel.MEDIUM -> 25
        RiskLevel.HIGH -> 50
        RiskLevel.CRITICAL -> 100
    }

    /** Уровень риска приложения по его признакам. */
    fun level(indicators: Collection<SpywareIndicator>): RiskLevel {
        val distinct = indicators.toSet()
        return when {
            SpywareIndicator.DEVICE_ADMIN in distinct &&
                SpywareIndicator.ACCESSIBILITY_SERVICE in distinct -> RiskLevel.CRITICAL
            SpywareIndicator.DEVICE_ADMIN in distinct ||
                SpywareIndicator.ACCESSIBILITY_SERVICE in distinct -> RiskLevel.HIGH
            SpywareIndicator.MODIFIED_AFTER_INSTALL in distinct -> RiskLevel.HIGH
            SpywareIndicator.DEBUG_SIGNATURE in distinct &&
                SpywareIndicator.SIDELOADED in distinct -> RiskLevel.HIGH
            distinct.size >= 2 -> RiskLevel.HIGH
            distinct.isNotEmpty() -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    /** Общий балл устройства: сумма весов, обрезанная до шкалы 0..100. */
    fun totalScore(levels: Iterable<RiskLevel>): Int =
        levels.sumOf { weight(it) }.coerceIn(0, 100)
}
