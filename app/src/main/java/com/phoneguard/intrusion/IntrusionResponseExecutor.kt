package com.phoneguard.intrusion

import android.Manifest
import android.content.Context
import android.util.Log
import android.content.Intent
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.phoneguard.R
import com.phoneguard.antitheft.SecurityService
import com.phoneguard.antitheft.StealthCameraService
import com.phoneguard.data.local.IntrusionEventDao
import com.phoneguard.data.preferences.PreferencesManager
import com.phoneguard.data.repository.FirewallRepository
import com.phoneguard.model.FirewallRule
import com.phoneguard.ui.IntrusionWarningActivity
import com.phoneguard.util.LogRedaction
import com.phoneguard.util.SmsSender
import com.phoneguard.vault.VaultSession
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

    /**
     * Приводит наблюдателя за приманками к текущей настройке защиты.
     *
     * Вызывается при каждом изменении настройки: выключенная защита не должна продолжать
     * писать события (правило — в [HoneypotLifecyclePolicy]).
     */
    suspend fun syncHoneypotState()
}

@Singleton
class AndroidIntrusionResponseExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val securityService: SecurityService,
    private val firewallRepository: FirewallRepository,
    private val intrusionEventDao: IntrusionEventDao,
    private val vaultSession: VaultSession
) : IntrusionResponseExecutor {

    /**
     * Scope наблюдателя создаётся лениво и обнуляется при остановке: отменённый
     * `CoroutineScope` переиспользовать нельзя, а после повторного включения защиты
     * наблюдатель должен снова работать.
     */
    @Volatile
    private var honeypotScope: CoroutineScope? = null

    private val honeypotReportGate = HoneypotReportGate()

    /** Антидребезг активных реакций: одно событие — одна сирена, одно SMS, одно фото. */
    private val reactionGate = IntrusionReactionGate()

    @Volatile
    private var honeypotObserver: HoneypotObserver? = null

    /**
     * Выполняет план реакции на событие.
     *
     * Информационные действия — предупреждение пользователю и выгрузка события в
     * `cacheDir` — выполняются **всегда**. Раньше единственная проверка
     * `if (!isAutoIntrusionResponseEnabled) return` стояла перед всем циклом, поэтому при
     * выключенном «автоответе» пользователь не видел ни одного предупреждения, а журнал
     * инцидентов не пополнялся ничем: пропадала не «агрессивная реакция», а сама фиксация
     * вторжения. Теперь настройка управляет только активными действиями (блокировка,
     * тревога, фото, SMS, приманки, сейф).
     */
    /**
     * Настройки реакции, снятые **один раз** на событие.
     *
     * Раньше `executeActive` читал `isIntrusionAlarmEnabled`, `isIntrusionPhotoEnabled`,
     * `isIntrusionSmsEnabled`, `isIntrusionHoneypotEnabled` и `isIntrusionBlockNetworkEnabled`
     * по одному разу на каждое действие: до шести обращений к DataStore на событие, каждое в
     * своей точке приостановки. При серии событий план выполнялся по «плавающим» настройкам —
     * половина реакций могла примениться со старым значением.
     */
    private data class ReactionSettings(
        val autoResponse: Boolean,
        val alarm: Boolean,
        val photo: Boolean,
        val sms: Boolean,
        val honeypot: Boolean,
        val blockNetwork: Boolean
    )

    private suspend fun loadSettings(): ReactionSettings = ReactionSettings(
        autoResponse = preferencesManager.isAutoIntrusionResponseEnabled.first(),
        alarm = preferencesManager.isIntrusionAlarmEnabled.first(),
        photo = preferencesManager.isIntrusionPhotoEnabled.first(),
        sms = preferencesManager.isIntrusionSmsEnabled.first(),
        honeypot = preferencesManager.isIntrusionHoneypotEnabled.first(),
        blockNetwork = preferencesManager.isIntrusionBlockNetworkEnabled.first()
    )

    override suspend fun execute(event: IntrusionEvent, actions: List<IntrusionResponseAction>) {
        val settings = loadSettings()

        actions.forEach { action ->
            val category = action.category()
            // Информационные действия безусловны, активные — только при включённом
            // автоответе. Правило живёт в IntrusionResponsePolicy (см. её KDoc: раньше
            // один `return` отключал и предупреждение, и запись события).
            if (!IntrusionResponsePolicy.shouldExecute(category, settings.autoResponse)) return@forEach

            // Серия одинаковых событий не должна давать серию одинаковых активных реакций
            // (пять правил фаервола, пять SMS, пять запусков камеры).
            if (IntrusionReactionGate.shouldGate(category) &&
                !reactionGate.shouldRun(
                    IntrusionReactionGate.keyFor(event, action),
                    SystemClock.elapsedRealtime()
                )
            ) {
                Log.d(TAG, "Reaction $category for ${event.type} suppressed as repeat")
                return@forEach
            }

            try {
                when (action) {
                    is IntrusionResponseAction.ShowWarning ->
                        showWarning(action.title, context.getString(action.messageRes))
                    IntrusionResponseAction.ExportLog -> exportLog(event)
                    else -> executeActive(event, action, settings)
                }
            } catch (e: Exception) {
                // Одно упавшее действие раньше отменяло весь остаток плана: падение
                // `blockPackage` оставляло пользователя без предупреждения и без фото.
                Log.w(TAG, "Intrusion action ${IntrusionReactionGate.actionName(action)} failed", e)
            }
        }
    }

    /** Соответствие действия политике; единственное место с этим маппингом. */
    private fun IntrusionResponseAction.category(): IntrusionResponsePolicy.Category = when (this) {
        is IntrusionResponseAction.ShowWarning -> IntrusionResponsePolicy.Category.WARNING
        IntrusionResponseAction.ExportLog -> IntrusionResponsePolicy.Category.LOG_EXPORT
        IntrusionResponseAction.LockVault -> IntrusionResponsePolicy.Category.LOCK_VAULT
        is IntrusionResponseAction.BlockPackage -> IntrusionResponsePolicy.Category.BLOCK_PACKAGE
        is IntrusionResponseAction.BlockNetwork -> IntrusionResponsePolicy.Category.BLOCK_NETWORK
        IntrusionResponseAction.StartAlarm -> IntrusionResponsePolicy.Category.ALARM
        IntrusionResponseAction.CapturePhoto -> IntrusionResponsePolicy.Category.PHOTO
        is IntrusionResponseAction.NotifyTrustedContacts ->
            IntrusionResponsePolicy.Category.NOTIFY_CONTACTS
        is IntrusionResponseAction.CreateHoneypot -> IntrusionResponsePolicy.Category.HONEYPOT
    }

    private suspend fun executeActive(
        event: IntrusionEvent,
        action: IntrusionResponseAction,
        settings: ReactionSettings
    ) {
        when (action) {
            is IntrusionResponseAction.BlockPackage -> blockPackage(action.packageName)
            is IntrusionResponseAction.BlockNetwork ->
                if (settings.blockNetwork) blockNetwork(action.packageName)
            // Запираем сейф PhoneGuard, а не экран всего устройства: `lockNow()` требовал
            // прав администратора (иначе молча ничего не делал) и гасил телефон, которым
            // пользователь в этот момент пользуется. Блокировка экрана осталась за
            // удалённой SMS-командой `LOCK`.
            IntrusionResponseAction.LockVault -> vaultSession.lock()
            // Только сирена: `activateLostMode()` пометил бы устройство «потерянным»
            // навсегда (флаг сохраняется и переживает перезапуск) из-за одного вторжения.
            IntrusionResponseAction.StartAlarm ->
                if (settings.alarm) securityService.soundAlarm()
            IntrusionResponseAction.CapturePhoto ->
                if (settings.photo) capturePhoto(event.id)
            is IntrusionResponseAction.NotifyTrustedContacts ->
                if (settings.sms) notifyTrustedContacts(context.getString(action.messageRes))
            is IntrusionResponseAction.CreateHoneypot ->
                if (settings.honeypot) createHoneypot(action.name, event)
            is IntrusionResponseAction.ShowWarning,
            IntrusionResponseAction.ExportLog -> Unit
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
        val target = packageName ?: run {
            Log.w(TAG, "Network block requested without package name")
            return
        }
        blockPackage(target)
    }

    private fun capturePhoto(eventId: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Раньше это был молчаливый `return`: снаружи «фото сделано» и «нет разрешения»
            // выглядели одинаково, и понять, почему в журнале нет снимка, было нельзя.
            Log.w(TAG, "Camera permission missing, photo skipped for event $eventId")
            return
        }
        val intent = Intent(context, StealthCameraService::class.java).apply {
            action = StealthCameraService.ACTION_TAKE_PHOTO
            putExtra(StealthCameraService.EXTRA_EVENT_ID, eventId)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+ запрещает запуск foreground-сервиса из фона.
            Log.w(TAG, "Cannot start stealth camera for event $eventId", e)
        }
    }

    private suspend fun notifyTrustedContacts(message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SMS permission missing, trusted contacts not notified")
            return
        }
        val contacts = securityService.getTrustedContacts()
        val recipients = contacts.ifEmpty {
            val backup = preferencesManager.backupNumber.first()
            if (backup.isNullOrBlank()) emptyList() else listOf(backup)
        }
        if (recipients.isEmpty()) {
            Log.w(TAG, "No trusted contact and no backup number, intrusion SMS skipped")
        }
        // Раньше здесь стоял `SmsManager.getDefault()` + `sendTextMessage()` без
        // разбиения: длинное предупреждение обрезалось, а «опасное» сообщение с
        // адресом и координатами вообще могло не отправиться.
        recipients.forEach { number ->
            SmsSender.send(context, number, context.getString(R.string.intrusion_sms_prefix, message))
        }
    }

    /**
     * Показывает предупреждение тем каналом, который на этом API реально работает.
     *
     * Раньше здесь был безусловный `startActivity`: с Android 10 система молча блокирует
     * запуск активити из фона, поэтому предупреждение о вторжении не видел никто
     * (правило выбора — в [IntrusionWarningDeliveryPolicy]).
     */
    private fun showWarning(title: String, message: String) {
        val delivery = IntrusionWarningDeliveryPolicy.choose(
            sdkInt = Build.VERSION.SDK_INT,
            appInForeground = isAppInForeground()
        )
        when (delivery) {
            WarningDelivery.ACTIVITY -> {
                // Если экран открыть не удалось (активити отключена политикой устройства),
                // уведомление — единственный оставшийся канал.
                if (!IntrusionWarningActivity.start(context, title, message)) {
                    IntrusionWarningNotifier.notify(context, title, message)
                }
            }
            WarningDelivery.NOTIFICATION -> IntrusionWarningNotifier.notify(context, title, message)
        }
    }

    /**
     * На переднем ли плане приложение.
     *
     * `getMyMemoryState` не требует разрешений и работает для собственного процесса; ошибку
     * чтения трактуем как «в фоне» — уведомление покажется в любом случае, а фоновая активити
     * на новых версиях всё равно не откроется.
     */
    private fun isAppInForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * Создаёт файлы-приманки и запускает наблюдатель за каталогом.
     *
     * Раньше приманки создавались, но никто их не отслеживал, поэтому
     * honeypot был бесполезен. Теперь любое открытие приманки фиксируется как
     * событие [IntrusionType.HONEYPOT_ACCESSED], и пользователь получает
     * предупреждение.
     */
    private suspend fun createHoneypot(name: String, event: IntrusionEvent) {
        // Единственный источник правды по приманкам — HoneypotManager: здесь раньше
        // была своя копия логики, которая писала «PhoneGuard honeypot <id>» и тем
        // самым выдавала приманку вредоносному ПО. Каталог и его создание — тоже его
        // забота, дублировать `mkdirs()` здесь больше не нужно.
        // Маркерный файл `<$name>.marker` больше не создаётся: он не подходил под
        // маску приманок, из-за чего не попадал ни в наблюдение (`isDecoyAccess`),
        // ни в уборку каталога и просто мусорил в filesDir. Приманки создаёт
        // HoneypotManager, он же отвечает за их имена и ротацию.
        val created = HoneypotManager().createDecoys(context, event)
        Log.d(TAG, "Honeypot '$name': создано приманок ${created.size} для события ${event.id}")
        syncHoneypotState()
    }

    /**
     * Приводит наблюдателя к настройке: включено — работает, выключено — молчит.
     *
     * Раньше наблюдатель запускался один раз и жил до смерти процесса: пользователь выключал
     * приманки в настройках, а события продолжали писаться в базу и показываться уведомлениями.
     */
    override suspend fun syncHoneypotState() {
        val enabled = runCatching { preferencesManager.isIntrusionHoneypotEnabled.first() }
            .getOrDefault(true)
        when (HoneypotLifecyclePolicy.decide(enabled, honeypotObserver != null)) {
            HoneypotLifecycleAction.START -> startHoneypotObserver()
            HoneypotLifecycleAction.STOP -> stopHoneypotObserver()
            HoneypotLifecycleAction.NONE -> Unit
        }
    }

    @Synchronized
    private fun startHoneypotObserver() {
        if (honeypotObserver != null) return
        val dir = File(context.filesDir, HoneypotManager.DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        val manager = HoneypotManager()
        val observer = HoneypotObserver(dir) { path ->
            // Колбэк приходит не из main-потока: всё, что нужно сделать — в IO.
            honeypotScopeRef().launch { handleDecoyAccess(path, manager) }
        }
        observer.start()
        honeypotObserver = observer
    }

    @Synchronized
    private fun stopHoneypotObserver() {
        honeypotObserver?.stop()
        honeypotObserver = null
        honeypotScope?.cancel()
        honeypotScope = null
    }

    @Synchronized
    private fun honeypotScopeRef(): CoroutineScope =
        honeypotScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { honeypotScope = it }

    /**
     * Реакция на доступ к файлу-приманке.
     *
     * Настройка проверяется заново: событие из очереди `inotify` может прийти уже после
     * выключения защиты, и тогда наблюдатель останавливается прямо здесь.
     */
    private suspend fun handleDecoyAccess(path: String, manager: HoneypotManager) {
        val enabled = runCatching { preferencesManager.isIntrusionHoneypotEnabled.first() }
            .getOrDefault(false)
        if (!HoneypotLifecyclePolicy.shouldReportAccess(enabled, manager.isDecoyAccess(path))) {
            if (!enabled) stopHoneypotObserver()
            return
        }
        // Собственные записи приманок тоже видны наблюдателю: события о
        // файлах, изменённых только что, и повторные события по тому же
        // пути игнорируем, иначе получаем ложные тревоги и цикл.
        if (!shouldReportHoneypotAccess(path)) return

        val decoyEvent = IntrusionEvent(
            type = IntrusionType.HONEYPOT_ACCESSED,
            severity = IntrusionSeverity.HIGH,
            evidence = mapOf("path" to path)
        )
        runCatching { intrusionEventDao.insert(decoyEvent.toEntity()) }
        showWarning(
            context.getString(R.string.honeypot_accessed_title),
            context.getString(R.string.honeypot_accessed_text, File(path).name)
        )
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
                // Выгрузка лежит в `cacheDir`, но её отдают в поддержку: адрес третьей
                // стороны маскируется так же, как в журнале фаервола.
                put("ipAddress", LogRedaction.maskIp(event.ipAddress))
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
