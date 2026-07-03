package com.phoneguard.fullscan.checks

import android.os.Build
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
 * ЯВНОЕ ОГРАНИЧЕНИЕ: на non-root устройстве нельзя прочитать checksums
 * системных файлов. Все проверки — эвристики, а не гарантии.
 */
@Singleton
class SystemIntegrityCheck @Inject constructor() {

    data class IntegrityResult(
        val isCompromised: Boolean,
        val details: List<String>
    )

    fun performCheck(): IntegrityResult {
        val details = mutableListOf<String>()
        var compromisedIndicators = 0

        // 1. Проверка Verified Boot (Android Verified Boot — AVB)
        // Через SystemProperties — если доступно без root
        val verifiedBootState = try {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getMethod("get", String::class.java)
            getMethod.invoke(null, "ro.boot.verifiedbootstate") as? String ?: "unknown"
        } catch (_: Exception) { "unknown" }

        val isBootUnlocked = try {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getMethod("get", String::class.java)
            val value = getMethod.invoke(null, "ro.boot.flash.locked") as? String ?: "1"
            value != "1"  // 1 = locked, 0 = unlocked, другое = unknown
        } catch (_: Exception) { false }

        details.add("Состояние Verified Boot: $verifiedBootState")
        details.add("Bootloader locked: ${!isBootUnlocked}")

        if (verifiedBootState == "orange" || verifiedBootState == "yellow") {
            details.add("⚠ Verified Boot в небезопасном состоянии ($verifiedBootState)")
            compromisedIndicators++
        }
        if (isBootUnlocked) {
            details.add("⚠ Загрузчик разблокирован")
            compromisedIndicators++
        }

        // 2. Проверка даты прошивки — старая прошивка может иметь уязвимости
        val buildDate = try {
            Build.TIME
        } catch (_: Exception) { 0L }
        if (buildDate > 0) {
            val yearsSinceBuild = (System.currentTimeMillis() - buildDate) / (365L * 24 * 60 * 60 * 1000)
            if (yearsSinceBuild >= 2) {
                details.add("⚠ Прошивке более ${yearsSinceBuild} лет — возможны уязвимости")
                compromisedIndicators++
            } else {
                details.add("Прошивка относительно свежая")
            }
        }

        // 3. Эвристическая проверка /system file permissions
        // Без root мы не можем читать /system, но можем проверить некоторые
        // известные индикаторы через существование файлов
        val suspiciousSystemFiles = listOf(
            "/system/bin/debuggerd",
            "/system/bin/su",
            "/data/local.prop"
        ).filter { File(it).exists() }
        if (suspiciousSystemFiles.isNotEmpty()) {
            details.add("⚠ Обнаружены подозрительные системные файлы: ${suspiciousSystemFiles.joinToString()}")
            compromisedIndicators++
        }

        // 4. Проверка Build.FINGERPRINT на кастомные прошивки
        val fingerprint = Build.FINGERPRINT ?: ""
        val isCustomRom = fingerprint.contains("lineage", ignoreCase = true) ||
                fingerprint.contains("custom", ignoreCase = true) ||
                fingerprint.contains("aosp", ignoreCase = true) ||
                !fingerprint.contains("release-keys", ignoreCase = true)
        if (isCustomRom && !fingerprint.contains("google", ignoreCase = true)) {
            details.add("⚠ Вероятно кастомная прошивка (fingerprint: ...${fingerprint.takeLast(30)})")
            compromisedIndicators++
        } else {
            details.add("Стандартная прошивка")
        }

        // ОГРАНИЧЕНИЕ: настоящая проверка checksums требует root-доступа
        details.add("")
        details.add("— Ограничение: полная проверка целостности /system требует root-доступа.")
        details.add("— Без root невозможно проверить checksums системных файлов.")
        details.add("— Результат основан на эвристиках и не является гарантией.")
        details.add("")

        return IntegrityResult(
            isCompromised = compromisedIndicators >= 2,
            details = details
        )
    }
}
