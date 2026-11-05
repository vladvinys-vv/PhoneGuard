package com.phoneguard.perm

/**
 * Какие возможности защиты ломаются без разрешений.
 *
 * Разрешения запрашивались один раз на онбординге, а дальше никто не проверял, что они на месте:
 * пользователь мог отозвать `CAMERA`, `SEND_SMS`, `READ_PHONE_STATE` или (на Android 13+)
 * `POST_NOTIFICATIONS` — и защита тихо деградировала. Хуже того, разные места реагировали
 * по-своему: где-то `return`, где-то `Log.w`, где-то уведомление.
 *
 * Здесь одна таблица «возможность → разрешение», из которой строится список ослабленных функций
 * для интерфейса. Правила чистые (никакого `Context`), поэтому проверяются тестом.
 */
object ProtectionCapabilityPolicy {

    /** Что именно перестаёт работать. */
    enum class Capability {
        /** Детектор подмены SIM: без `READ_PHONE_STATE` он не может прочитать идентификатор SIM. */
        SIM_SWAP_DETECTION,

        /** Скрытая съёмка нарушителя по событию вторжения. */
        INTRUSION_PHOTO,

        /** SMS доверенным контактам при вторжении и подмене SIM. */
        INTRUSION_SMS,

        /** Координаты доверенному контакту при подмене SIM. */
        TRUSTED_CONTACT_LOCATION,

        /** Предупреждения и уведомления о событиях (Android 13+). */
        INTRUSION_WARNING
    }

    /** Разрешения, от которых зависит защита. */
    enum class CapabilityPermission {
        PHONE_STATE,
        CAMERA,
        SEND_SMS,
        FINE_LOCATION,
        POST_NOTIFICATIONS
    }

    data class Requirement(val capability: Capability, val permission: CapabilityPermission)

    /** Версия Android, с которой уведомления требуют явного разрешения. */
    const val NOTIFICATIONS_RUNTIME_FROM_SDK = 33

    private val BASE_REQUIREMENTS: List<Requirement> = listOf(
        Requirement(Capability.SIM_SWAP_DETECTION, CapabilityPermission.PHONE_STATE),
        Requirement(Capability.INTRUSION_PHOTO, CapabilityPermission.CAMERA),
        Requirement(Capability.INTRUSION_SMS, CapabilityPermission.SEND_SMS),
        Requirement(Capability.TRUSTED_CONTACT_LOCATION, CapabilityPermission.FINE_LOCATION)
    )

    /**
     * Таблица требований для конкретной версии системы.
     *
     * До Android 13 `POST_NOTIFICATIONS` нет вовсе: спрашивать про него бессмысленно, и
     * «нет разрешения» там означало бы ложное предупреждение у всех пользователей.
     */
    fun requirements(sdkInt: Int): List<Requirement> =
        if (sdkInt >= NOTIFICATIONS_RUNTIME_FROM_SDK) {
            BASE_REQUIREMENTS + Requirement(
                Capability.INTRUSION_WARNING,
                CapabilityPermission.POST_NOTIFICATIONS
            )
        } else {
            BASE_REQUIREMENTS
        }

    /** Ослабленные возможности — в порядке объявления [Capability], без повторов. */
    fun degraded(granted: Set<CapabilityPermission>, sdkInt: Int): List<Capability> {
        val missing = requirements(sdkInt)
            .filter { it.permission !in granted }
            .map { it.capability }
            .toSet()
        return Capability.values().filter { it in missing }
    }

    /** Все ли возможности в порядке. */
    fun isFullyProtected(granted: Set<CapabilityPermission>, sdkInt: Int): Boolean =
        degraded(granted, sdkInt).isEmpty()
}
