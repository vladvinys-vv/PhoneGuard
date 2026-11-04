package com.phoneguard.fullscan.checks

import com.phoneguard.util.NamePatternMatcher

/**
 * Признаки «инструмента перехвата» среди включённых служб доступности.
 *
 * Вынесено из [AccessibilityServiceCheck]: решение (какой шаблон совпал) влияет на
 * текст находки, который видит пользователь, но проверить его тестом в файле с
 * `PackageManager` было нельзя.
 */
internal object AccessibilityHeuristics {

    /** Подстроки в имени пакета или ярлыке, характерные для автокликеров и шпионов. */
    val SUSPICIOUS_PATTERNS = listOf(
        "autoclick", "auto.click", "accessibility.click",
        "spy", "monitor", "keylog", "screenshot",
        "recorder", "tracker", "watcher", "grabber"
    )

    /**
     * Первый совпавший признак (видимость для тестов).
     *
     * Сравнение идёт через [NamePatternMatcher]: имя и шаблон режутся на слова, поэтому
     * `auto.click` находит `AutoClicker`, а `keylog` не находится в постороннем слове.
     * Регистр приводится через `Locale.ROOT`, а не через неявную локаль: результат не должен
     * зависеть от языка системы (закреплено тестом, который переключает локаль на турецкую).
     *
     * @param appLabel ярлык приложения; `null` — приложение удалено
     */
    fun matchingPattern(packageName: String, appLabel: String?): String? =
        NamePatternMatcher.firstMatch(packageName, appLabel, SUSPICIOUS_PATTERNS)
}
