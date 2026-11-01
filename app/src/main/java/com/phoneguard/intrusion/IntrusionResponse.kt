package com.phoneguard.intrusion

import androidx.annotation.StringRes
import com.phoneguard.R
import java.io.File

sealed interface IntrusionResponseAction {
    data class BlockPackage(val packageName: String) : IntrusionResponseAction
    data class BlockNetwork(val packageName: String?) : IntrusionResponseAction
    /**
     * Запереть сейф PhoneGuard: следующее открытие снова требует PIN/биометрию.
     *
     * Экран устройства это действие не блокирует (для этого есть удалённая команда `LOCK`).
     */
    data object LockVault : IntrusionResponseAction
    data object StartAlarm : IntrusionResponseAction
    data object CapturePhoto : IntrusionResponseAction
    /** @param messageRes текст SMS — ресурс, чтобы сообщение было на языке устройства. */
    data class NotifyTrustedContacts(@StringRes val messageRes: Int) : IntrusionResponseAction
    data class ShowWarning(val title: String, @StringRes val messageRes: Int) : IntrusionResponseAction
    data class CreateHoneypot(val name: String) : IntrusionResponseAction
    data object ExportLog : IntrusionResponseAction
}

class IntrusionResponseOrchestrator {
    fun plan(event: IntrusionEvent): List<IntrusionResponseAction> {
        val actions = mutableListOf<IntrusionResponseAction>()
        when (event.severity) {
            IntrusionSeverity.LOW -> {
                actions += IntrusionResponseAction.ExportLog
            }
            IntrusionSeverity.MEDIUM -> {
                actions += IntrusionResponseAction.ShowWarning(
                    title = "PhoneGuard",
                    messageRes = R.string.intrusion_warning_suspicious
                )
                actions += IntrusionResponseAction.ExportLog
            }
            IntrusionSeverity.HIGH -> {
                event.packageName?.let { actions += IntrusionResponseAction.BlockPackage(it) }
                actions += IntrusionResponseAction.BlockNetwork(event.packageName)
                actions += IntrusionResponseAction.LockVault
                actions += IntrusionResponseAction.StartAlarm
                actions += IntrusionResponseAction.CapturePhoto
                actions += IntrusionResponseAction.NotifyTrustedContacts(R.string.intrusion_sms_suspicious_activity)
                // Для самого события «приманку открыли» новую приманку не создаём:
                // иначе обработчик снова пишет файлы в наблюдаемый каталог и
                // провоцирует бесконечную цепочку событий.
                if (event.type != IntrusionType.HONEYPOT_ACCESSED) {
                    actions += IntrusionResponseAction.CreateHoneypot("intrusion_${event.timestamp}")
                }
                actions += IntrusionResponseAction.ExportLog
            }
            IntrusionSeverity.CRITICAL -> {
                event.packageName?.let { actions += IntrusionResponseAction.BlockPackage(it) }
                actions += IntrusionResponseAction.BlockNetwork(null)
                actions += IntrusionResponseAction.LockVault
                actions += IntrusionResponseAction.StartAlarm
                actions += IntrusionResponseAction.CapturePhoto
                actions += IntrusionResponseAction.NotifyTrustedContacts(R.string.intrusion_sms_critical)
                if (event.type != IntrusionType.HONEYPOT_ACCESSED) {
                    actions += IntrusionResponseAction.CreateHoneypot("critical_${event.timestamp}")
                }
                actions += IntrusionResponseAction.ShowWarning(
                    title = "PhoneGuard",
                    messageRes = R.string.intrusion_warning_blocked
                )
                actions += IntrusionResponseAction.ExportLog
            }
        }
        return actions
    }
}

class HoneypotManager {

    /**
     * Создаёт файлы-приманки и убирает старые.
     *
     * Раньше каждый инцидент добавлял новый набор `<timestamp>_passwords.txt` и никто их
     * не удалял: каталог рос без ограничений, а наполнение приманок было текстом
     * «PhoneGuard honeypot 42» — вредонос сразу понимает, что его ждут. Теперь:
     *  - держим не больше [MAX_DECOY_SETS] наборов (старые удаляются при создании);
     *  - содержимое похоже на настоящую находку для malware.
     */
    fun createDecoys(context: android.content.Context, event: IntrusionEvent): List<File> =
        createDecoys(File(context.filesDir, DIR_NAME), event)

    /**
     * То же, но с явно заданным каталогом.
     *
     * Отдельная перегрузка нужна тестам: `Context` на JVM не создать, а логика уборки
     * (`MAX_DECOY_SETS`) работает с обычным `java.io.File` и проверяется на реальной
     * временной папке — без Android и Robolectric.
     */
    fun createDecoys(dir: File, event: IntrusionEvent): List<File> {
        if (!dir.exists()) dir.mkdirs()
        val created = decoyNames(event).map { name ->
            File(dir, name).apply {
                if (!exists()) runCatching { writeText(contentFor(name)) }
            }
        }
        // Чистим после создания: иначе в каталоге остаётся MAX_DECOY_SETS + 1 набор.
        pruneOldDecoys(dir)
        return created
    }

    fun decoyNames(event: IntrusionEvent): List<String> =
        DECOY_BASE_NAMES.map { "${event.timestamp}_$it" }

    /** Доступ к приманке определяем по точному имени файла, а не по вхождению подстроки. */
    fun isDecoyAccess(path: String): Boolean {
        val name = java.io.File(path).name
        return DECOY_PATTERN.matches(name)
    }

    private fun pruneOldDecoys(dir: java.io.File) {
        val decoys = dir.listFiles { file -> DECOY_PATTERN.matches(file.name) } ?: return
        val sets = decoys
            .map { it.name.substringBefore('_').toLongOrNull() ?: 0L }
            .distinct()
            .sortedDescending()
        val expired = sets.drop(MAX_DECOY_SETS).toSet()
        if (expired.isEmpty()) return
        decoys.filter { it.name.substringBefore('_').toLongOrNull() in expired }
            .forEach { runCatching { it.delete() } }
    }

    private fun contentFor(fileName: String): String = when {
        fileName.endsWith("passwords.txt") ->
            "mail: kunde.beispiel@example.com\npassword: Sommer2021!\npin: 4821\n"
        fileName.endsWith("wallet_backup.json") ->
            """{"mnemonic":"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about","created":"2021-04-11"}"""
        else -> buildString {
            appendLine("IMG_20210411_182233.jpg")
            appendLine("IMG_20210411_182310.jpg")
            append("IMG_20210411_182402.jpg")
        }
    }

    companion object {
        /** Каталог приманок внутри `filesDir` (используется и уборкой данных). */
        const val DIR_NAME = "phoneguard_honeypot"

        private const val MAX_DECOY_SETS = 3
        private val DECOY_BASE_NAMES = listOf("passwords.txt", "wallet_backup.json", "private_photos")
        private val DECOY_PATTERN = Regex("""^\d+_(${DECOY_BASE_NAMES.joinToString("|") { Regex.escape(it) }})$""")
    }
}
