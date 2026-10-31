package com.phoneguard.intrusion

sealed interface IntrusionResponseAction {
    data class BlockPackage(val packageName: String) : IntrusionResponseAction
    data class BlockNetwork(val packageName: String?) : IntrusionResponseAction
    data object LockVault : IntrusionResponseAction
    data object StartAlarm : IntrusionResponseAction
    data object CapturePhoto : IntrusionResponseAction
    data class NotifyTrustedContacts(val message: String) : IntrusionResponseAction
    data class ShowWarning(val title: String, val message: String) : IntrusionResponseAction
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
                    message = "Подозрительная активность обнаружена"
                )
                actions += IntrusionResponseAction.ExportLog
            }
            IntrusionSeverity.HIGH -> {
                event.packageName?.let { actions += IntrusionResponseAction.BlockPackage(it) }
                actions += IntrusionResponseAction.BlockNetwork(event.packageName)
                actions += IntrusionResponseAction.LockVault
                actions += IntrusionResponseAction.StartAlarm
                actions += IntrusionResponseAction.CapturePhoto
                actions += IntrusionResponseAction.NotifyTrustedContacts("Обнаружена попытка взлома")
                actions += IntrusionResponseAction.CreateHoneypot("intrusion_${event.timestamp}")
                actions += IntrusionResponseAction.ExportLog
            }
            IntrusionSeverity.CRITICAL -> {
                event.packageName?.let { actions += IntrusionResponseAction.BlockPackage(it) }
                actions += IntrusionResponseAction.BlockNetwork(null)
                actions += IntrusionResponseAction.LockVault
                actions += IntrusionResponseAction.StartAlarm
                actions += IntrusionResponseAction.CapturePhoto
                actions += IntrusionResponseAction.NotifyTrustedContacts("Критическая попытка взлома")
                actions += IntrusionResponseAction.CreateHoneypot("critical_${event.timestamp}")
                actions += IntrusionResponseAction.ShowWarning(
                    title = "PhoneGuard",
                    message = "Устройство защищено. Попытка взлома заблокирована"
                )
                actions += IntrusionResponseAction.ExportLog
            }
        }
        return actions
    }
}

class HoneypotManager {
    fun createDecoys(context: android.content.Context, event: IntrusionEvent): List<File> {
        val dir = java.io.File(context.filesDir, "phoneguard_honeypot")
        if (!dir.exists()) dir.mkdirs()
        return decoyNames(event).map { name ->
            java.io.File(dir, name).apply { if (!exists()) writeText("PhoneGuard honeypot ${event.id}") }
        }
    }

    fun decoyNames(event: IntrusionEvent): List<String> =
        listOf("passwords.txt", "wallet_backup.json", "private_photos").map { "${event.timestamp}_$it" }

    fun isDecoyAccess(path: String): Boolean =
        path.contains("passwords.txt") || path.contains("wallet_backup.json") || path.contains("private_photos")
}
