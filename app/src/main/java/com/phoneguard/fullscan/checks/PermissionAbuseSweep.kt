package com.phoneguard.fullscan.checks

import android.Manifest
import android.content.pm.ApplicationInfo

/**
 * Отбор приложений для проверки опасных комбинаций разрешений.
 *
 * Раньше эти три правила жили внутри `PermissionAbuseCheck.performCheck()` и не проверялись
 * ни одним выполняемым тестом: нужен был `Context`, `PackageManager` и `mockk`. Ошибка здесь
 * стоит дорого — она либо прячет шпионское приложение, либо обвиняет системное.
 *
 * Правила:
 *  1. **Системные приложения не проверяются.** Предустановленное ПО (`FLAG_SYSTEM`) и
 *     обновлённое системное (`FLAG_UPDATED_SYSTEM_APP`) приходит с завода и не выбирается
 *     пользователем; попади оно в отчёт — «критических» находок было бы десятка.
 *  2. **Считаются только выданные разрешения.** Приложение может заявить в манифесте камеру
 *     и микрофон и не получить их. Раньше проверялись *запрошенные* разрешения, поэтому
 *     отчёт называл «шпионским» приложение, у которого доступа к камере нет вовсе.
 *  3. **Группа срабатывает, если выдано хотя бы одно её разрешение.** Группа «телефон»
 *     содержит `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` и `CALL_PHONE`: одного из них
 *     достаточно, чтобы группа считалась опасной.
 */
internal object PermissionAbuseSweep {

    /**
     * Каталог групп: какие разрешения образуют группу.
     *
     * `POST_NOTIFICATIONS` намеренно отсутствует — это самое безобидное runtime-разрешение,
     * и раньше оно давало ложные срабатывания.
     */
    val CATALOG: Map<PermissionAbuseCheck.PermissionGroup, List<String>> = mapOf(
        PermissionAbuseCheck.PermissionGroup.CAMERA to listOf(
            Manifest.permission.CAMERA
        ),
        PermissionAbuseCheck.PermissionGroup.MICROPHONE to listOf(
            Manifest.permission.RECORD_AUDIO
        ),
        PermissionAbuseCheck.PermissionGroup.LOCATION to listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ),
        PermissionAbuseCheck.PermissionGroup.CONTACTS to listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        ),
        PermissionAbuseCheck.PermissionGroup.SMS to listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        ),
        PermissionAbuseCheck.PermissionGroup.PHONE to listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        ),
        PermissionAbuseCheck.PermissionGroup.STORAGE to listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    /** Предустановленное или обновлённое системное приложение — вне отчёта. */
    fun isSystemApp(flags: Int): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /**
     * Группы разрешений, которые приложение реально получило.
     *
     * @param requested разрешения из манифеста;
     * @param catalog каталог групп (по умолчанию [CATALOG]);
     * @param isGranted проверка «разрешение выдано» (`PackageManager.checkPermission`).
     *
     * `isGranted` — последний параметр намеренно: вызов выглядит как
     * `grantedGroups(permissions) { it in granted }`, и его нельзя случайно перепутать
     * с каталогом.
     */
    fun grantedGroups(
        requested: Collection<String>,
        catalog: Map<PermissionAbuseCheck.PermissionGroup, List<String>> = CATALOG,
        isGranted: (String) -> Boolean
    ): Set<PermissionAbuseCheck.PermissionGroup> {
        if (requested.isEmpty()) return emptySet()
        val granted = requested.filterTo(mutableSetOf(), isGranted)
        if (granted.isEmpty()) return emptySet()

        // Порядок групп — как в каталоге, чтобы объяснение в отчёте было стабильным.
        return catalog.entries
            .filter { (_, permissions) -> permissions.any { it in granted } }
            .mapTo(linkedSetOf()) { (group, _) -> group }
    }
}
