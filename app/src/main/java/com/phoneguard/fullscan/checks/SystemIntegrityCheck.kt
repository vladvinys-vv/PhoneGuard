package com.phoneguard.fullscan.checks

import android.content.Context
import android.os.Build
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System Integrity Check.
 *
 * Проверяет целостность системы: состояние Verified Boot, загрузчика,
 * критические системные файлы. Без root-доступа проверка НЕ МОЖЕТ быть
 * полной — доступ к /system ограничен, поэтому все проверки эвристические.
 *
 * Исправления:
 *  - `android.os.SystemProperties` скрыт с Android 9 (non-SDK interface), поэтому
 *    рефлексия всегда падала и результат был «unknown». Добавлен запасной путь
 *    через `getprop`;
 *  - `/system/bin/debuggerd` есть на **каждом** устройстве — он больше не
 *    считается подозрительным файлом (раньше давал ложные срабатывания);
 *  - кастомная прошивка сама по себе не считается компрометацией: индикатором
 *    остаются только `test-keys`/`userdebug` в fingerprint;
 *  - тексты диагностики берутся из ресурсов: раньше отчёт по целостности был
 *    русским в любой локали.
 */
@Singleton
class SystemIntegrityCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class IntegrityResult(
        val isCompromised: Boolean,
        val details: List<String>
    )

    fun performCheck(): IntegrityResult {
        val details = mutableListOf<String>()
        var compromisedIndicators = 0

        // 1. Verified Boot (Android Verified Boot) и состояние загрузчика.
        val verifiedBootState = readSystemProperty("ro.boot.verifiedbootstate") ?: "unknown"
        val flashLocked = readSystemProperty("ro.boot.flash.locked")
        val isBootUnlocked = flashLocked != null && flashLocked != "1"

        details.add(context.getString(R.string.integrity_verified_boot, verifiedBootState))
        details.add(
            when (flashLocked) {
                "1" -> context.getString(R.string.integrity_bootloader_locked)
                null -> context.getString(R.string.integrity_bootloader_unknown)
                else -> context.getString(R.string.integrity_bootloader_unlocked, flashLocked)
            }
        )

        if (verifiedBootState == "orange" || verifiedBootState == "yellow") {
            details.add(context.getString(R.string.integrity_verified_boot_unsafe, verifiedBootState))
            compromisedIndicators++
        }
        if (isBootUnlocked) {
            details.add(context.getString(R.string.integrity_bootloader_unlocked_warn))
            compromisedIndicators++
        }

        // 2. Дата сборки прошивки — старая прошивка несёт известные уязвимости.
        val buildDate = Build.TIME
        if (buildDate > 0) {
            val yearsSinceBuild = (System.currentTimeMillis() - buildDate) /
                (365L * 24 * 60 * 60 * 1000)
            if (yearsSinceBuild >= 2) {
                details.add(context.getString(R.string.integrity_build_old, yearsSinceBuild.toInt()))
                compromisedIndicators++
            } else {
                details.add(context.getString(R.string.integrity_build_fresh))
            }
        }

        // 3. Известные индикаторы перезаписанного системного раздела.
        val suspiciousSystemFiles = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/xbin/daemonsu",
            "/system/app/Superuser.apk",
            "/data/local.prop"
        ).filter { File(it).exists() }
        if (suspiciousSystemFiles.isNotEmpty()) {
            details.add(
                context.getString(
                    R.string.integrity_system_files_found,
                    suspiciousSystemFiles.joinToString()
                )
            )
            compromisedIndicators++
        } else {
            details.add(context.getString(R.string.integrity_system_files_none))
        }

        // 4. Признаки отладочной/кастомной сборки в fingerprint.
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val tags = Build.TAGS.orEmpty()
        val hasDebugTags = tags.contains("test-keys", ignoreCase = true) ||
            tags.contains("dev-keys", ignoreCase = true) ||
            fingerprint.contains("userdebug", ignoreCase = true) ||
            fingerprint.contains("eng.", ignoreCase = true)
        if (hasDebugTags) {
            details.add(context.getString(R.string.integrity_debug_build, tags))
            compromisedIndicators++
        } else if (fingerprint.contains("lineage", ignoreCase = true) ||
            fingerprint.contains("custom", ignoreCase = true)
        ) {
            // Кастомная прошивка — информация, а не компрометация.
            details.add(
                context.getString(R.string.integrity_custom_rom, fingerprint.takeLast(30))
            )
        } else {
            details.add(context.getString(R.string.integrity_standard_rom))
        }

        // ОГРАНИЧЕНИЕ: настоящая проверка checksums требует root-доступа
        details.add(context.getString(R.string.integrity_limit_root))
        details.add(context.getString(R.string.integrity_limit_checksums))
        details.add(context.getString(R.string.integrity_limit_heuristics))

        return IntegrityResult(
            isCompromised = compromisedIndicators >= 2,
            details = details
        )
    }

    /**
     * Читает системное свойство: сначала через рефлексию (работает не везде),
     * затем через `getprop` (публичный бинарник, доступен приложениям).
     */
    private fun readSystemProperty(name: String): String? {
        runCatching {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, name) as? String
            if (!value.isNullOrBlank()) return value
        }

        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.errorStream?.close()
            process.waitFor()
            process.destroy()
            output.ifBlank { null }
        }.getOrNull()
    }
}
