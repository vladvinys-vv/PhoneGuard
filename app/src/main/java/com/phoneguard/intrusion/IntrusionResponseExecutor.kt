package com.phoneguard.intrusion

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.antitheft.DeviceAdminReceiverImpl
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.antitheft.StealthCameraService
import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import com.phoneguard.ui.IntrusionWarningActivity
import com.phoneguard.util.SmsSender
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    private val honeypotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val honeypotReportGate = HoneypotReportGate()
    private var honeypotObserver: HoneypotObserver? = null

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
                is IntrusionResponseAction.NotifyTrustedContacts -> if (preferencesManager.isIntrusionSmsEnabled.first()) notifyTrustedContacts(context.getString(action.messageRes))
                is IntrusionResponseAction.ShowWarning -> showWarning(action.title, context.getString(action.messageRes))
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
        // Раньше здесь стоял `SmsManager.getDefault()` + `sendTextMessage()` без
        // разбиения: длинное предупреждение обрезалось, а «опасное» сообщение с
        // адресом и координатами вообще могло не отправиться.
        recipients.forEach { number ->
            SmsSender.send(context, number, context.getString(R.string.intrusion_sms_prefix, message))
        }
    }

    private fun showWarning(title: String, message: String) {
        IntrusionWarningActivity.start(context, title, message)
    }

    /**
     * Создаёт файлы-приманки и запускает наблюдатель за каталогом.
     *
     * Раньше приманки создавались, но никто их не отслеживал, поэтому
     * honeypot был бесполезен. Теперь любое открытие приманки фиксируется как
     * событие [IntrusionType.HONEYPOT_ACCESSED], и пользователь получает
     * предупреждение.
     */
    private fun createHoneypot(name: String, event: IntrusionEvent) {
        // Единственный источник правды по приманкам — HoneypotManager: здесь раньше
        // была своя копия логики, которая писала «PhoneGuard honeypot <id>» и тем
        // самым выдавала приманку вредоносному ПО.
        val dir = File(context.filesDir, HoneypotManager.DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        // Маркерный файл `<$name>.marker` больше не создаётся: он не подходил под
        // маску приманок, из-за чего не попадал ни в наблюдение (`isDecoyAccess`),
        // ни в уборку каталога и просто мусорил в filesDir. Приманки создаёт
        // HoneypotManager, он же отвечает за их имена и ротацию.
        val created = HoneypotManager().createDecoys(context, event)
        Log.d(TAG, "Honeypot '$name': создано приманок ${created.size} для события ${event.id}")
        ensureHoneypotObserver(dir)
    }

    @Synchronized
    private fun ensureHoneypotObserver(dir: File) {
        if (honeypotObserver != null) return
        val manager = HoneypotManager()
        val observer = HoneypotObserver(dir) { path ->
            // Колбэк приходит не из main-потока: всё, что нужно сделать — в IO.
            honeypotScope.launch {
                if (!manager.isDecoyAccess(path)) return@launch
                // Собственные записи приманок тоже видны наблюдателю: события о
                // файлах, изменённых только что, и повторные события по тому же
                // пути игнорируем, иначе получаем ложные тревоги и цикл.
                if (!shouldReportHoneypotAccess(path)) return@launch
                val decoyEvent = IntrusionEvent(
                    type = IntrusionType.HONEYPOT_ACCESSED,
                    severity = IntrusionSeverity.HIGH,
                    evidence = mapOf("path" to path)
                )
                runCatching { intrusionEventDao.insert(decoyEvent.toEntity()) }
                showWarning(
                    context.getString(R.string.honeypot_accessed_title),
                    context.getString(
                        R.string.honeypot_accessed_text,
                        File(path).name
                    )
                )
            }
        }
        observer.start()
        honeypotObserver = observer
    }

    /**
     * Антидребезг для honeypot: не чаще одного события на путь за 10 минут.
     *
     * Сама арифметика вынесена в [HoneypotReportGate] — её можно проверить
     * JVM-тестом, а интервалы там считаются по монотонным часам.
     */
    private fun shouldReportHoneypotAccess(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return honeypotReportGate.shouldReport(
            path = path,
            lastModifiedWallMs = file.lastModified(),
            nowWallMs = System.currentTimeMillis(),
            nowElapsedMs = SystemClock.elapsedRealtime()
        )
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

    private companion object {
        const val TAG = "IntrusionResponseExecutor"

        /** Пороги антидребезга honeypot живут в [HoneypotReportGate]. */
    }
}
