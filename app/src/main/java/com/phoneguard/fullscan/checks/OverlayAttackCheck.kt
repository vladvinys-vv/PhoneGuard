package com.phoneguard.fullscan.checks

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.phoneguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Overlay Attack (Tapjacking) Check.
 *
 * Malware использует SYSTEM_ALERT_WINDOW для наложения невидимых окон
 * поверх легитимных приложений, перехватывая клики пользователя.
 *
 * Исправление: раньше проверка вообще не смотрела на разрешение оверлея — она
 * перебирала все установленные приложения и искала слова вроде `overlay`/`bubble` в
 * имени пакета. В отчёт попадали безобидные лаунчеры «Bubble Browser» без права
 * рисовать поверх других окон, а настоящее вредоносное ПО с нейтральным именем —
 * нет. Теперь кандидат обязан реально иметь `SYSTEM_ALERT_WINDOW` (проверяется
 * через `AppOpsManager`), и уже среди таких приложений ищутся подозрительные имена.
 */
@Singleton
class OverlayAttackCheck @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class OverlayResult(
        val appsWithOverlay: List<SuspiciousOverlayApp>,
        val hasDangerousOverlays: Boolean
    )

    data class SuspiciousOverlayApp(
        val packageName: String,
        val appName: String,
        val riskReason: String
    )

    fun performCheck(): OverlayResult {
        val pm = context.packageManager
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        val suspicious = installedApps
            .asSequence()
            .filterNot { it.isSystemApp() }
            .mapNotNull { appInfo ->
                // Сначала дешёвое сравнение по имени, затем системный вызов AppOps.
                val appName = pm.getApplicationLabel(appInfo).toString()
                val matchedPattern = matchingPattern(appInfo.packageName, appName)
                    ?: return@mapNotNull null
                if (!hasOverlayPermission(appInfo)) return@mapNotNull null

                SuspiciousOverlayApp(
                    packageName = appInfo.packageName,
                    appName = appName,
                    riskReason = context.getString(R.string.overlay_reason, matchedPattern)
                )
            }
            .distinctBy { it.packageName }
            .toList()

        return OverlayResult(suspicious, suspicious.isNotEmpty())
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** Разрешено ли приложению рисовать поверх других окон (SYSTEM_ALERT_WINDOW). */
    private fun hasOverlayPermission(appInfo: ApplicationInfo): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    appInfo.uid,
                    appInfo.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    appInfo.uid,
                    appInfo.packageName
                )
            }
        } catch (_: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    internal companion object {
        // Приложения, которые могут использовать оверлеи для атак
        private val SUSPICIOUS_OVERLAY_PATTERNS = listOf(
            "screen.filter", "bluelight", "dimmer", "night.filter",
            "screen.dim", "overlay", "floating", "bubble",
            "chat.head", "pop-up"
        )

        /**
         * Приводит имя к «слитному» виду: `chat.head`, `chat head`, `chat_head` и `ChatHead`
         * должны совпадать, ведь разделители в названиях пишут кто как.
         *
         * Раньше сравнение шло простым `contains(pattern)`, поэтому шаблоны с точкой и дефисом
         * (`chat.head`, `pop-up`, `screen.filter`) не находили **ни одного** реального имени:
         * приложение `com.x.chathead` с ярлыком `ChatHead` проходило проверку как безопасное.
         */
        private fun normalize(value: String): String =
            value.lowercase().filter { it.isLetterOrDigit() }

        /** Первый совпавший признак имени пакета/приложения (видимость для тестов). */
        fun matchingPattern(packageName: String, appLabel: String): String? {
            val packageNameNormalized = normalize(packageName)
            val appLabelNormalized = normalize(appLabel)
            return SUSPICIOUS_OVERLAY_PATTERNS.firstOrNull { pattern ->
                val needle = normalize(pattern)
                needle.isNotEmpty() &&
                    (packageNameNormalized.contains(needle) ||
                        appLabelNormalized.contains(needle))
            }
        }
    }
}
