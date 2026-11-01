package com.phoneguard.util

/**
 * Идентификаторы уведомлений приложения — в одном месте.
 *
 * Зачем: они уже дважды пересекались, и каждый раз это была тихая потеря
 * уведомлений, а не падение.
 *  * Блокировка звонка использовала `1001` — тот же id, что у постоянного
 *    уведомления VPN, поэтому «звонок заблокирован» затирал статус брандмауэра;
 *  * уведомления о новых пакетах лежали в диапазоне `2000..6095` и накрывали
 *    `4101` — подтверждение подмены SIM: установка приложения могла стереть
 *    предупреждение, которое просит пользователя подтвердить SIM.
 *
 * Теперь диапазоны фиксированы и проверяются тестом (`NotificationIdsTest`):
 * одиночные id — ниже 10 000, уведомления о пакетах — от 20 000.
 */
object NotificationIds {

    /** Постоянное уведомление VPN-брандмауэра. */
    const val VPN_FOREGROUND = 1001

    /** Постоянное уведомление детектора подглядывания. */
    const val SHOULDER_SURFER_FOREGROUND = 1002

    /** Предупреждение «кто-то смотрит в экран». */
    const val SHOULDER_SURFER_ALERT = 1003

    /** Заблокированный звонок. */
    const val BLOCKED_CALL = 1101

    /** Заблокированное или подозрительное SMS. */
    const val BLOCKED_SMS = 1102

    /** Подтверждение подмены SIM. */
    const val SIM_SWAP = 4101

    /** Тревога по SMS-команде (`#PG:PIN:ALARM`). */
    const val SMS_ALARM = 8101

    /** Скрытая съёмка при вторжении (foreground-сервис). */
    const val STEALTH_CAMERA = 9001

    /**
     * База для уведомлений «подозрительная подпись нового приложения».
     * Диапазон вынесен за пределы всех одиночных id.
     */
    const val PACKAGE_ADDED_BASE = 20_000

    /** Маска: 4096 значений на пакет. */
    private const val PACKAGE_ADDED_MASK = 0x0FFF

    /** Допустимый диапазон id для уведомлений о пакетах. */
    val PACKAGE_ADDED_RANGE: IntRange = PACKAGE_ADDED_BASE..(PACKAGE_ADDED_BASE + PACKAGE_ADDED_MASK)

    /** Отдельный id на пакет, чтобы новые установки не затирали друг друга. */
    fun packageAdded(packageName: String): Int =
        PACKAGE_ADDED_BASE + (packageName.hashCode() and PACKAGE_ADDED_MASK)
}
