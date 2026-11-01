package com.phoneguard.fullscan.checks

import java.util.Locale

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
     * Сравнение идёт через `lowercase(Locale.ROOT)`, а не через сравнение регистра с
     * неявной локалью: результат не должен зависеть от языка системы и от версии
     * платформы (это закреплено тестом, который переключает локаль на турецкую).
     *
     * @param appLabel ярлык приложения; `null` — приложение удалено
     */
    fun matchingPattern(packageName: String, appLabel: String?): String? {
        val pkg = packageName.lowercase(Locale.ROOT)
        val label = appLabel?.lowercase(Locale.ROOT)
        return SUSPICIOUS_PATTERNS.firstOrNull { pattern ->
            pkg.contains(pattern) || label?.contains(pattern) == true
        }
    }
}
