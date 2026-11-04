package com.phoneguard.shouldersurfer

/**
 * Правила «кто-то смотрит в экран» — без камеры и ML Kit.
 *
 * Раньше пороги жили прямо в `handleFaces()` сервиса: наклон головы, поворот в профиль,
 * вероятность открытых глаз и интервал между предупреждениями были «магическими» числами рядом
 * с `ImageProxy`. Ошибка в них не роняет приложение — она либо превращает защиту в генератор
 * ложных тревог (телефон пищит каждые полминуты), либо делает её мёртвой (лицо есть, реакции нет).
 * Теперь это проверяемая функция.
 *
 * Зафиксированные правила:
 *  - лицо «смотрит в экран», если наклон головы (pitch) не больше ±20° и поворот (yaw) не больше
 *    ±30° — в профиль человек на экран не смотрит;
 *  - глаза открыты, если вероятность открытости больше 0.3 (ML Kit отдаёт вероятность, а не факт);
 *  - если ML Kit не отдал вероятность вовсе (лицо частично перекрыто), считаем глаза открытыми:
 *    так было до выноса правил, и менять это — продуктовое решение, а не исправление;
 *  - предупреждения — не чаще раза в [MIN_ALERT_INTERVAL_MS]: иначе телефон «пищит» постоянно.
 */
internal object ShoulderSurferDetectionPolicy {

    /** Максимальный наклон головы вперёд/назад, при котором лицо ещё «перед экраном». */
    const val MAX_PITCH_DEGREES = 20f

    /** Максимальный поворот головы в сторону (профиль — уже не подглядывание в экран). */
    const val MAX_YAW_DEGREES = 30f

    /** Порог вероятности открытых глаз. */
    const val MIN_EYE_OPEN_PROBABILITY = 0.3f

    /** Не чаще одного предупреждения в полминуты. */
    const val MIN_ALERT_INTERVAL_MS = 30_000L

    /** Значение, если ML Kit не отдал вероятность открытости глаз. */
    const val UNKNOWN_EYE_PROBABILITY = 1.0f

    fun isFacingScreen(pitchDegrees: Float, yawDegrees: Float): Boolean =
        pitchDegrees in -MAX_PITCH_DEGREES..MAX_PITCH_DEGREES &&
            yawDegrees in -MAX_YAW_DEGREES..MAX_YAW_DEGREES

    fun areEyesOpen(
        leftProbability: Float?,
        rightProbability: Float?
    ): Boolean = (leftProbability ?: UNKNOWN_EYE_PROBABILITY) > MIN_EYE_OPEN_PROBABILITY &&
        (rightProbability ?: UNKNOWN_EYE_PROBABILITY) > MIN_EYE_OPEN_PROBABILITY

    /** Полное правило одного лица: смотрит в экран и глаза открыты. */
    fun isSuspiciousFace(
        pitchDegrees: Float,
        yawDegrees: Float,
        leftEyeProbability: Float?,
        rightEyeProbability: Float?
    ): Boolean = isFacingScreen(pitchDegrees, yawDegrees) &&
        areEyesOpen(leftEyeProbability, rightEyeProbability)

    /**
     * Пора ли показывать предупреждение.
     *
     * @param nowElapsedMs монотонное время сейчас
     * @param lastAlertElapsedMs монотонное время последнего предупреждения (`0` — ещё не было)
     */
    fun shouldAlert(
        nowElapsedMs: Long,
        lastAlertElapsedMs: Long,
        intervalMs: Long = MIN_ALERT_INTERVAL_MS
    ): Boolean {
        if (lastAlertElapsedMs <= 0L) return true
        val elapsed = nowElapsedMs - lastAlertElapsedMs
        // Отрицательная разница означает «часы поехали»: предупреждать безопаснее, чем молчать.
        return elapsed < 0L || elapsed >= intervalMs
    }
}
