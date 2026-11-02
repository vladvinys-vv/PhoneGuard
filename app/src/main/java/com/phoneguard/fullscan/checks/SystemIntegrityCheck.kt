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
        val signals = mutableSetOf<IntegrityPolicy.Signal>()

        // 1. Verified Boot (Android Verified Boot) и состояние загрузчика.
        val verifiedBootState = readSystemProperty("ro.boot.verifiedbootstate") ?: "unknown"
        val flashLocked = readSystemProperty("ro.boot.flash.locked")
        val isBootUnlocked = IntegrityPolicy.isBootloaderUnlocked(flashLocked)

        details.add(context.getString(R.string.integrity_verified_boot, verifiedBootState))
        details.add(
            when (flashLocked) {
                "1" -> context.getString(R.string.integrity_bootloader_locked)
                null -> context.getString(R.string.integrity_bootloader_unknown)
                else -> context.getString(R.string.integrity_bootloader_unlocked, flashLocked)
            }
        )

        if (IntegrityPolicy.isVerifiedBootUnsafe(verifiedBootState)) {
            details.add(context.getString(R.string.integrity_verified_boot_unsafe, verifiedBootState))
            signals += IntegrityPolicy.Signal.VERIFIED_BOOT_UNSAFE
        }
        if (isBootUnlocked) {
            details.add(context.getString(R.string.integrity_bootloader_unlocked_warn))
            signals += IntegrityPolicy.Signal.BOOTLOADER_UNLOCKED
        }

        // 2. Дата сборки прошивки — старая прошивка несёт известные уязвимости.
        val buildDate = Build.TIME
        val nowMs = System.currentTimeMillis()
        if (buildDate > 0) {
            if (IntegrityPolicy.isStaleBuild(buildDate, nowMs)) {
                details.add(
                    context.getString(
                        R.string.integrity_build_old,
                        IntegrityPolicy.buildAgeYears(buildDate, nowMs)
                    )
                )
                // Слабая улика: сама по себе не делает систему скомпрометированной.
                signals += IntegrityPolicy.Signal.STALE_FIRMWARE
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
            signals += IntegrityPolicy.Signal.SYSTEM_ROOT_FILES
        } else {
            details.add(context.getString(R.string.integrity_system_files_none))
        }

        // 4. Признаки отладочной/кастомной сборки в fingerprint.
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val tags = Build.TAGS.orEmpty()
        if (IntegrityPolicy.hasDebugBuildMarks(tags, fingerprint)) {
            details.add(context.getString(R.string.integrity_debug_build, tags))
            signals += IntegrityPolicy.Signal.DEBUG_BUILD
        } else if (IntegrityPolicy.isCustomRom(fingerprint)) {
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
            isCompromised = IntegrityPolicy.isCompromised(signals),
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
            // `redirectErrorStream(true)` обязателен: если сначала читать stdout, а stderr
            // закрывать после, процесс может заблокироваться на заполненном буфере stderr.
            val process = ProcessBuilder("getprop", name)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            process.destroy()
            output.ifBlank { null }
        }.getOrNull()
    }
}
