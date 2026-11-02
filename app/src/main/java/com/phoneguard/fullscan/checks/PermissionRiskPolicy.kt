package com.phoneguard.fullscan.checks

/**
 * Оценка риска по набору выданных групп разрешений — без Android и ресурсов.
 *
 * Вынесено из [PermissionAbuseCheck] по двум причинам:
 *  - логика проверялась только Robolectric-тестом, который в офлайн-прогоне не запускается,
 *    то есть правила «что считать шпионским ПО» фактически не выполнялись ни разу;
 *  - вместе с правилами переехала и найденная нестыковка: `SMS + PHONE` есть в списке
 *    критических комбинаций, а тест ждал для него `HIGH` — на настоящем прогоне он падал.
 *
 * Правила (в порядке проверки):
 *  1. любая критическая комбинация → `CRITICAL`;
 *  2. `SMS` + хотя бы одна другая группа → `HIGH` (перехват сообщений);
 *  3. только `LOCATION` → `MEDIUM`;
 *  4. две группы из «следящего» набора (`CAMERA`, `MICROPHONE`, `LOCATION`, `CONTACTS`) → `HIGH`.
 *     Этого правила раньше не было: `CAMERA + LOCATION` или `CONTACTS + LOCATION` (фото
 *     с геометкой, выгрузка адресной книги) не попадали в отчёт вообще;
 *  5. три и более групп → `HIGH`.
 */
internal object PermissionRiskPolicy {

    /** Чувствительные группы разрешений. */
    enum class Group { CAMERA, MICROPHONE, LOCATION, CONTACTS, SMS, PHONE, STORAGE }

    enum class Level { CRITICAL, HIGH, MEDIUM }

    /** Почему сработало правило — по нему выбирается текст объяснения. */
    enum class Reason { CRITICAL_COMBO, SMS_PLUS_OTHER, BACKGROUND_LOCATION, SENSITIVE_PAIR, MANY_SENSITIVE }

    /**
     * @param matched группы, которые дали срабатывание (для текста «опасная комбинация: …»)
     */
    data class Verdict(
        val level: Level,
        val reason: Reason,
        val matched: Set<Group>
    )

    /** Наборы, которые считаются шпионским поведением целиком. */
    private val CRITICAL_COMBOS = listOf(
        setOf(Group.CAMERA, Group.MICROPHONE, Group.LOCATION),
        setOf(Group.SMS, Group.CONTACTS, Group.LOCATION),
        setOf(Group.CAMERA, Group.MICROPHONE),
        setOf(Group.SMS, Group.PHONE),
        setOf(Group.MICROPHONE, Group.LOCATION)
    )

    /** Две группы из этого набора — уже слежка, даже если критической комбинации нет. */
    private val SPY_GROUPS = setOf(Group.CAMERA, Group.MICROPHONE, Group.LOCATION, Group.CONTACTS)

    private const val MANY_SENSITIVE_THRESHOLD = 3

    fun assess(granted: Set<Group>): Verdict? {
        if (granted.isEmpty()) return null

        CRITICAL_COMBOS.firstOrNull { granted.containsAll(it) }?.let { combo ->
            return Verdict(Level.CRITICAL, Reason.CRITICAL_COMBO, combo)
        }

        if (Group.SMS in granted && granted.size >= 2) {
            return Verdict(Level.HIGH, Reason.SMS_PLUS_OTHER, granted)
        }

        if (granted.size == 1 && Group.LOCATION in granted) {
            return Verdict(Level.MEDIUM, Reason.BACKGROUND_LOCATION, granted)
        }

        val spyPair = granted.intersect(SPY_GROUPS)
        if (spyPair.size >= 2) {
            return Verdict(Level.HIGH, Reason.SENSITIVE_PAIR, spyPair)
        }

        if (granted.size >= MANY_SENSITIVE_THRESHOLD) {
            return Verdict(Level.HIGH, Reason.MANY_SENSITIVE, granted)
        }

        return null
    }
}
