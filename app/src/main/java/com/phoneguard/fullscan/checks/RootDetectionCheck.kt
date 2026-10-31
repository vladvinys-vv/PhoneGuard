package com.phoneguard.fullscan.checks

import android.content.Context
import com.phoneguard.R
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

        // 1. Проверка su binary по стандартным путям
        val suFound = suPaths.any { File(it).exists() }
        if (suFound) {
            details.add(context.getString(R.string.root_detail_su_found))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_su_not_found))
        }

        // 2. Проверка build tags
        val buildTags = android.os.Build.TAGS ?: ""
        val hasTestKeys = testKeysTags.any { buildTags.contains(it, ignoreCase = true) }
        if (hasTestKeys) {
            details.add(context.getString(R.string.root_detail_tags_suspicious, buildTags))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_tags_ok, buildTags))
        }

        // 3. Проверка установленных root-приложений
        val pm = context.packageManager
        val rootApps = rootPackagePrefixes.filter { prefix ->
            try {
                // getApplicationInfo вместо устаревшего getPackageInfo(..., 0)
                pm.getApplicationInfo(prefix, 0)
                true
            } catch (_: Exception) { false }
        }
        if (rootApps.isNotEmpty()) {
            details.add(context.getString(R.string.root_detail_apps_found, rootApps.joinToString()))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_apps_none))
        }

        // 4. Проверка возможности выполнения su через Runtime.exec
        // Безопасно — в try-catch, приложение не упадёт
        val canExecuteSu = try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.errorStream?.close()
            process.waitFor()
            process.destroy()
            reader.isNotBlank()
        } catch (_: Exception) { false }
        if (canExecuteSu) {
            details.add(context.getString(R.string.root_detail_which_su))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_no_path_su))
        }

        // 5. Проверка /system на запись (writable system partition)
        // Эвристика без root: проверяем существование файла .system_writable_marker
        // Это НЕ ГАРАНТИРУЕТ реальную writable-систему, только эвристика
        // Реальную запись в /system не пробуем: на заблокированном устройстве это
        // исключение, а на рутованном — необратимое изменение системы. Поэтому
        // проверяем известные маркеры перезаписанного системного раздела.
        val systemWritable = try {
            File("/system/xbin/.su").exists() ||
                File("/system/bin/.ext").exists() ||
                File("/system/app/Superuser.apk").exists()
        } catch (_: Exception) { false }
        if (systemWritable) {
            details.add(context.getString(R.string.root_detail_system_writable))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_system_intact))
        }

        // 6. Проверка SELinux enforcing
        val isEnforcing = try {
            val selinux = File("/sys/fs/selinux/enforce")
            selinux.exists() && selinux.readText().trim() == "1"
        } catch (_: Exception) { true }
        if (!isEnforcing) {
            details.add(context.getString(R.string.root_detail_selinux_permissive))
            rootIndicators++
        } else {
            details.add(context.getString(R.string.root_detail_selinux_enforcing))
        }

        // Каждая проверка добавляет ровно одну строку в details (и при
        // срабатывании, и при отсутствии индикатора), поэтому их количество и
        // есть число проверок — раньше здесь была константа 6, которая
        // разъезжалась при добавлении новой проверки.
        val totalChecks = details.size.coerceAtLeast(1)
        val confidence = rootIndicators.toFloat() / totalChecks.toFloat()
        return RootCheckResult(
            isRooted = rootIndicators >= 3,
            details = details,
            confidence = confidence
        )
    }
}
