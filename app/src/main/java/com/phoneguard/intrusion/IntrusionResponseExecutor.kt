package com.phoneguard.intrusion

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.phoneguard.antitheft.DeviceAdminReceiverImpl
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.antitheft.StealthCameraService
import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import com.phoneguard.ui.IntrusionWarningActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface IntrusionResponseExecutor {
    suspend fun execute(event: IntrusionEvent, actions: List<IntrusionResponseAction>)
}

@Singleton
class AndroidIntrusionResponseExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val securityService: SecurityService,
    private val firewallRepository: FirewallRepository,
    private val intrusionEventDao: IntrusionEventDao
) : IntrusionResponseExecutor {

    override suspend fun execute(event: IntrusionEvent, actions: List<IntrusionResponseAction>) {
        val autoEnabled = preferencesManager.isAutoIntrusionResponseEnabled.first()
        if (!autoEnabled) return

        actions.forEach { action ->
            when (action) {
                is IntrusionResponseAction.BlockPackage -> blockPackage(action.packageName)
                is IntrusionResponseAction.BlockNetwork -> if (preferencesManager.isIntrusionBlockNetworkEnabled.first()) blockNetwork(action.packageName)
                IntrusionResponseAction.LockVault -> lockDevice()
                IntrusionResponseAction.StartAlarm -> if (preferencesManager.isIntrusionAlarmEnabled.first()) securityService.activateLostMode()
                IntrusionResponseAction.CapturePhoto -> if (preferencesManager.isIntrusionPhotoEnabled.first()) capturePhoto(event.id)
                is IntrusionResponseAction.NotifyTrustedContacts -> if (preferencesManager.isIntrusionSmsEnabled.first()) notifyTrustedContacts(action.message)
                is IntrusionResponseAction.ShowWarning -> showWarning(action.title, action.message)
                is IntrusionResponseAction.CreateHoneypot -> if (preferencesManager.isIntrusionHoneypotEnabled.first()) createHoneypot(action.name, event)
                IntrusionResponseAction.ExportLog -> exportLog(event)
            }
        }
    }

    private suspend fun blockPackage(packageName: String) {
        firewallRepository.upsertRule(
            FirewallRule(
                packageName = packageName,
                appName = packageName,
                blockAll = true,
                blockWifi = true,
                blockMobile = true,
                allowByDefault = false
            )
        )
    }

    private suspend fun blockNetwork(packageName: String?) {
        val target = packageName ?: return
        blockPackage(target)
    }

    private fun lockDevice() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = ComponentName(context, DeviceAdminReceiverImpl::class.java)
            if (dpm.isAdminActive(component)) {
                dpm.lockNow()
            }
        } catch (_: Exception) {}
    }

    private fun capturePhoto(eventId: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, StealthCameraService::class.java).apply {
            action = StealthCameraService.ACTION_TAKE_PHOTO
            putExtra(StealthCameraService.EXTRA_EVENT_ID, eventId)
        }
        try {
            context.startForegroundService(intent)
        } catch (_: Exception) {}
    }

    private suspend fun notifyTrustedContacts(message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val contacts = securityService.getTrustedContacts()
        val recipients = contacts.ifEmpty {
            val backup = preferencesManager.backupNumber.first()
            if (backup.isNullOrBlank()) emptyList() else listOf(backup)
        }
        recipients.forEach { number ->
            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(number, null, "PhoneGuard: $message", null, null)
            } catch (_: Exception) {}
        }
    }

    private fun showWarning(title: String, message: String) {
        IntrusionWarningActivity.start(context, title, message)
    }

    private fun createHoneypot(name: String, event: IntrusionEvent) {
        val dir = File(context.filesDir, "phoneguard_honeypot")
        if (!dir.exists()) dir.mkdirs()
        HoneypotManager().decoyNames(event).forEach { decoy ->
            try {
                File(dir, decoy).writeText("PhoneGuard honeypot ${event.id}")
            } catch (_: Exception) {}
        }
        // marker for future observer
        File(dir, "$name.marker").writeText(event.id.toString())
    }

    private fun exportLog(event: IntrusionEvent) {
        try {
            val file = File(context.cacheDir, "intrusion_${event.id}.json")
            val json = JSONObject().apply {
                put("id", event.id)
                put("timestamp", event.timestamp)
                put("type", event.type.name)
                put("severity", event.severity.name)
                put("evidence", JSONObject(event.evidence))
                put("uid", event.uid)
                put("packageName", event.packageName)
                put("ipAddress", event.ipAddress)
                put("photoPath", event.photoPath)
            }
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
    }
}
