package com.phoneguard.fullscan.checks

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root Detection Check.
 *
 * Использует множественные эвристики для обнаружения root-доступа.
 * Без root-прав некоторые проверки могут быть ограничены — смотри комментарии.
 * НИ ОДНА ИЗ ЭТИХ ПРОВЕРОК НЕ ДАЁТ 100% ГАРАНТИИ — это эвристики.
 */
@Singleton
class RootDetectionCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class RootCheckResult(
        val isRooted: Boolean,
        val details: List<String>,
        val confidence: Float // 0.0 .. 1.0
    )

    /** Стандартные пути к su binary */
    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/xbin/su",
        "/system/bin/.ext/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    /** Пакеты известных root-приложений */
    private val rootPackagePrefixes = listOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.thirdparty.superuser",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch"
    )

    /** Build tags, указывающие на root */
    private val testKeysTags = listOf("test-keys", "dev-keys", "eng")

    fun performCheck(): RootCheckResult {
        val details = mutableListOf<String>()
        var rootIndicators = 0
        val totalChecks = 6

        // 1. Проверка su binary по стандартным путям
        val suFound = suPaths.any { File(it).exists() }
        if (suFound) {
            details.add("Обнаружен su binary по стандартному пути")
            rootIndicators++
        } else {
            details.add("su binary не найден по стандартным путям")
        }

        // 2. Проверка build tags
        val buildTags = android.os.Build.TAGS ?: ""
        val hasTestKeys = testKeysTags.any { buildTags.contains(it, ignoreCase = true) }
        if (hasTestKeys) {
            details.add("Build tags содержат '$buildTags' (test-keys/dev-keys)")
            rootIndicators++
        } else {
            details.add("Build tags в норме: $buildTags")
        }

        // 3. Проверка установленных root-приложений
        val pm = context.packageManager
        val rootApps = rootPackagePrefixes.filter { prefix ->
            try {
                pm.getPackageInfo(prefix, 0)
                true
            } catch (_: Exception) { false }
        }
        if (rootApps.isNotEmpty()) {
            details.add("Обнаружены root-приложения: ${rootApps.joinToString()}")
            rootIndicators++
        } else {
            details.add("Root-приложения не обнаружены")
        }

        // 4. Проверка возможности выполнения su через Runtime.exec
        // Безопасно — в try-catch, приложение не упадёт
        val canExecuteSu = try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = process.inputStream.bufferedReader().readText().trim()
            process.destroy()
            reader.isNotBlank()
        } catch (_: Exception) { false }
        if (canExecuteSu) {
            details.add("Возможно выполнение 'which su' — su доступен в PATH")
            rootIndicators++
        } else {
            details.add("su не найден в PATH через Runtime.exec")
        }

        // 5. Проверка /system на запись (writable system partition)
        // Эвристика без root: проверяем существование файла .system_writable_marker
        // Это НЕ ГАРАНТИРУЕТ реальную writable-систему, только эвристика
        val systemWritable = try {
            val testFile = File("/system/test_phoneguard.tmp")
            // Мы не пытаемся реально записать — это может упасть на locked device.
            // Вместо этого проверяем, есть ли известные маркеры unlocked system:
            File("/system/xbin/.su").exists() ||
            File("/system/bin/.ext").exists()
        } catch (_: Exception) { false }
        if (systemWritable) {
            details.add("Системный раздел выглядит перезаписанным (обнаружены маркеры)")
            rootIndicators++
        } else {
            details.add("Маркеры перезаписи /system не обнаружены")
        }

        // 6. Проверка SELinux enforcing
        val isEnforcing = try {
            val selinux = File("/sys/fs/selinux/enforce")
            selinux.exists() && selinux.readText().trim() == "1"
        } catch (_: Exception) { true }
        if (!isEnforcing) {
            details.add("SELinux не в режиме enforcing (возможен root)")
            rootIndicators++
        } else {
            details.add("SELinux в режиме enforcing")
        }

        val confidence = rootIndicators.toFloat() / totalChecks.toFloat()
        return RootCheckResult(
            isRooted = rootIndicators >= 3,
            details = details,
            confidence = confidence
        )
    }
}
