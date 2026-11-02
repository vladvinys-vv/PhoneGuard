package com.phoneguard.simswap

/**
 * Что должно произойти, когда пользователь подтверждает или опровергает подмену SIM.
 *
 * Раньше эти правила были записаны прямо в `SimSwapDetector.confirmCurrentSimSwap()` и
 * `rejectCurrentSimSwap()` рядом с репозиторием, настройками, уведомлениями и
 * `SecurityService`, поэтому проверить их можно было только на устройстве. При этом
 * разница между двумя ветками неочевидна и легко ломается при правках:
 *
 *  - подтверждение («я сам поменял SIM») — событие помечается подтверждённым, снимается
 *    флаг «неподтверждённая подмена», уведомление убирается, **lost mode не включается**;
 *  - отказ («я SIM не менял») — то же самое плюс метка `isAttackSuspected` и **включение
 *    lost mode** (тревога, отправка координат доверенным контактам);
 *  - если незакрытого события нет (пользователь нажал кнопку из настроек), флаги всё
 *    равно выставляются: иначе баннер «подтвердите SIM» оставался бы на экране.
 */
object SimSwapConfirmPolicy {

    enum class Choice { CONFIRM, REJECT }

    data class Plan(
        /** Пометить последнее событие подтверждённым (только если оно есть). */
        val confirmLatestEvent: Boolean,
        /** Пометить событие как подозрение на атаку. */
        val markAttackSuspected: Boolean,
        /** Снять флаг «есть неподтверждённая подмена». */
        val clearUnconfirmedFlag: Boolean,
        /** Убрать уведомление «Вы поменяли SIM?». */
        val cancelNotification: Boolean,
        /** Включить lost mode и разослать координаты. */
        val activateLostMode: Boolean
    )

    fun plan(choice: Choice, hasPendingEvent: Boolean): Plan = when (choice) {
        Choice.CONFIRM -> Plan(
            confirmLatestEvent = hasPendingEvent,
            markAttackSuspected = false,
            clearUnconfirmedFlag = true,
            cancelNotification = true,
            activateLostMode = false
        )
        Choice.REJECT -> Plan(
            confirmLatestEvent = hasPendingEvent,
            markAttackSuspected = hasPendingEvent,
            clearUnconfirmedFlag = true,
            cancelNotification = true,
            activateLostMode = true
        )
    }
}
