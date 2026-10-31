package com.phoneguard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.phoneguard.util.PinAttemptLimiter
import com.phoneguard.util.SecurityUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "phoneguard_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        /** Файл-зеркало для выбранного языка (читается синхронно из Activity). */
        const val LOCALE_PREFS = "phoneguard_locale"
        const val LOCALE_KEY = "app_language"

        private val SUPPORTED_LANGUAGES = setOf("ru", "en", "de")

        /** Язык системы, если приложение его поддерживает; иначе язык по умолчанию (values/ = ru). */
        fun defaultLanguage(): String {
            val system = java.util.Locale.getDefault().language
            return if (system in SUPPORTED_LANGUAGES) system else "ru"
        }

        /** Выбранный язык без корутин; `null` — использовать язык системы. */
        fun readLanguage(context: Context): String? =
            context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .getString(LOCALE_KEY, null)

        private val KEY_PIN_HASH = stringPreferencesKey("pin_code_hash")

        /** Счётчик неудачных вводов PIN и время окончания блокировки (см. [PinAttemptLimiter]). */
        private val KEY_PIN_FAILED_ATTEMPTS = intPreferencesKey("pin_failed_attempts")
        private val KEY_PIN_LOCKED_UNTIL = longPreferencesKey("pin_locked_until")
        private val KEY_BACKUP_NUMBER = stringPreferencesKey("backup_number")
        private val KEY_SIM_LOCK_ENABLED = booleanPreferencesKey("sim_lock_enabled")
        private val KEY_DEVICE_ADMIN_ENABLED = booleanPreferencesKey("device_admin_enabled")
        private val KEY_PHOTO_ON_FAILED_ATTEMPTS = booleanPreferencesKey("photo_on_failed_attempts")
        private val KEY_REMOTE_ALARM_ENABLED = booleanPreferencesKey("remote_alarm_enabled")
        private val KEY_IS_PRO = booleanPreferencesKey("is_pro")
        // Чек всего телефона
        private val KEY_DEVICE_AUDIT_SCORE = intPreferencesKey("device_audit_score")
        private val KEY_DEVICE_AUDIT_ISSUES = intPreferencesKey("device_audit_issues")
        private val KEY_DEVICE_AUDIT_TIME = longPreferencesKey("device_audit_time")
        private val KEY_LAST_SCAN_TIME = longPreferencesKey("last_scan_time")
        private val KEY_BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown")
        private val KEY_BLOCK_HIDDEN = booleanPreferencesKey("block_hidden")
        private val KEY_BLOCK_INTERNATIONAL = booleanPreferencesKey("block_international")

        // SimSwap
        private val KEY_LAST_KNOWN_IMSI = stringPreferencesKey("last_known_imsi")
        private val KEY_SIM_SWAP_CONFIRMED = booleanPreferencesKey("sim_swap_confirmed")
        private val KEY_SIM_SWAP_UNCONFIRMED = booleanPreferencesKey("sim_swap_unconfirmed")
        private val KEY_SENSITIVE_APPS_GUARD_ENABLED = booleanPreferencesKey("sensitive_apps_guard_enabled")

        // Intrusion response
        private val KEY_AUTO_INTRUSION_RESPONSE = booleanPreferencesKey("auto_intrusion_response")
        private val KEY_INTRUSION_ALARM = booleanPreferencesKey("intrusion_alarm")
        private val KEY_INTRUSION_PHOTO = booleanPreferencesKey("intrusion_photo")
        private val KEY_INTRUSION_SMS = booleanPreferencesKey("intrusion_sms")
        private val KEY_INTRUSION_BLOCK_NETWORK = booleanPreferencesKey("intrusion_block_network")
        private val KEY_INTRUSION_HONEYPOT = booleanPreferencesKey("intrusion_honeypot")

        // Shoulder Surfer
        private val KEY_SHOULDER_SURFER_ENABLED = booleanPreferencesKey("shoulder_surfer_enabled")

        // Threat intelligence (opt-in, по умолчанию выключено)
        private val KEY_THREAT_INTEL_ENABLED = booleanPreferencesKey("threat_intel_enabled")

        /** Засеяна ли локальная база спам-номеров из `assets/spam_numbers.json`. */
        private val KEY_SPAM_DB_SEEDED = booleanPreferencesKey("spam_db_seeded")

        // Theme
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")

        // Onboarding
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        private val KEY_CONSENT_DONE = booleanPreferencesKey("consent_done")

        // Scheduled scan
        private val KEY_SCHEDULED_SCAN_ENABLED = booleanPreferencesKey("scheduled_scan_enabled")
    }

    // PIN Hash
    val pinCodeHash: Flow<String?> = context.dataStore.data.map { it[KEY_PIN_HASH] }
    val hasPinSet: Flow<Boolean> = pinCodeHash.map { it != null }

    suspend fun savePinCode(pin: String) {
        val hashedPin = SecurityUtils.hashPin(pin)
        context.dataStore.edit {
            it[KEY_PIN_HASH] = hashedPin
            // Новый PIN — новый счётчик попыток: старая блокировка не должна мешать.
            it.remove(KEY_PIN_FAILED_ATTEMPTS)
            it.remove(KEY_PIN_LOCKED_UNTIL)
        }
    }

    /**
     * Проверяет PIN с ограничением числа попыток.
     *
     * Раньше проверка не ограничивалась ничем: SMS-команды `#PG:PIN:…` можно было
     * подбирать бесконечно. Теперь после [PinAttemptLimiter.MAX_ATTEMPTS] неудач
     * проверка отклоняется на [PinAttemptLimiter.LOCKOUT_MS], а успешный ввод
     * сбрасывает счётчик. Состояние живёт в DataStore, поэтому перезагрузка
     * устройства блокировку не снимает.
     */
    suspend fun verifyPin(pin: String): Boolean {
        val preferences = context.dataStore.data.first()
        val now = System.currentTimeMillis()
        val state = PinAttemptLimiter.State(
            attempts = preferences[KEY_PIN_FAILED_ATTEMPTS] ?: 0,
            lockedUntil = preferences[KEY_PIN_LOCKED_UNTIL] ?: 0L
        )
        if (state.isLocked(now)) return false

        val storedHash = preferences[KEY_PIN_HASH] ?: return false
        val isValid = SecurityUtils.verifyPin(pin, storedHash)
        val next = if (isValid) {
            PinAttemptLimiter.afterSuccess()
        } else {
            PinAttemptLimiter.afterFailure(state, now)
        }
        context.dataStore.edit { edit ->
            if (next.attempts == 0) edit.remove(KEY_PIN_FAILED_ATTEMPTS) else edit[KEY_PIN_FAILED_ATTEMPTS] = next.attempts
            if (next.lockedUntil == 0L) edit.remove(KEY_PIN_LOCKED_UNTIL) else edit[KEY_PIN_LOCKED_UNTIL] = next.lockedUntil
        }
        return isValid
    }

    /** Сколько ещё действует блокировка ввода PIN (0 — не заблокирован). */
    suspend fun pinLockoutRemainingMs(): Long {
        val preferences = context.dataStore.data.first()
        val state = PinAttemptLimiter.State(
            attempts = preferences[KEY_PIN_FAILED_ATTEMPTS] ?: 0,
            lockedUntil = preferences[KEY_PIN_LOCKED_UNTIL] ?: 0L
        )
        return state.remainingMs(System.currentTimeMillis())
    }

    suspend fun clearPinCode() {
        context.dataStore.edit { it.remove(KEY_PIN_HASH) }
    }

    // Backup number
    val backupNumber: Flow<String?> = context.dataStore.data.map { it[KEY_BACKUP_NUMBER] }

    suspend fun saveBackupNumber(number: String) {
        context.dataStore.edit { it[KEY_BACKUP_NUMBER] = number }
    }

    // SIM lock
    val isSimLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SIM_LOCK_ENABLED] ?: false }

    suspend fun setSimLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SIM_LOCK_ENABLED] = enabled }
    }

    // Device admin
    val isDeviceAdminEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_DEVICE_ADMIN_ENABLED] ?: false }

    suspend fun setDeviceAdminEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DEVICE_ADMIN_ENABLED] = enabled }
    }

    // Photo on failed attempts
    val isPhotoOnFailedAttemptsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_PHOTO_ON_FAILED_ATTEMPTS] ?: false }

    suspend fun setPhotoOnFailedAttemptsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PHOTO_ON_FAILED_ATTEMPTS] = enabled }
    }

    // Remote alarm
    val isRemoteAlarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMOTE_ALARM_ENABLED] ?: false }

    suspend fun setRemoteAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REMOTE_ALARM_ENABLED] = enabled }
    }

    // Pro status
    // Pro-функции разблокированы по умолчанию (личное использование), но флаг
    // теперь реально сохраняется: иначе после включения монетизации интерфейс
    // уже нельзя было бы перевести в платный режим без переустановки.
    val isPro: Flow<Boolean> = context.dataStore.data.map { it[KEY_IS_PRO] ?: true }

    suspend fun setPro(isPro: Boolean) {
        context.dataStore.edit { it[KEY_IS_PRO] = isPro }
    }

    // Last scan
    val lastScanTime: Flow<Long?> = context.dataStore.data.map { it[KEY_LAST_SCAN_TIME] }

    suspend fun saveLastScanTime(time: Long) {
        context.dataStore.edit { it[KEY_LAST_SCAN_TIME] = time }
    }

    // Block rules
    val blockUnknownNumbers: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLOCK_UNKNOWN] ?: false }
    val blockHiddenNumbers: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLOCK_HIDDEN] ?: false }
    val blockInternationalNumbers: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLOCK_INTERNATIONAL] ?: false }

    suspend fun setBlockUnknownNumbers(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BLOCK_UNKNOWN] = enabled }
    }

    suspend fun setBlockHiddenNumbers(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BLOCK_HIDDEN] = enabled }
    }

    suspend fun setBlockInternationalNumbers(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BLOCK_INTERNATIONAL] = enabled }
    }

    // SimSwap
    val lastKnownImsi: Flow<String> = context.dataStore.data.map { it[KEY_LAST_KNOWN_IMSI] ?: "" }
    val isSimSwapConfirmed: Flow<Boolean> = context.dataStore.data.map { it[KEY_SIM_SWAP_CONFIRMED] ?: true }
    val isSimSwapUnconfirmed: Flow<Boolean> = context.dataStore.data.map { it[KEY_SIM_SWAP_UNCONFIRMED] ?: false }
    val isSensitiveAppsGuardEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SENSITIVE_APPS_GUARD_ENABLED] ?: true }

    suspend fun setLastKnownImsi(imsi: String) {
        context.dataStore.edit { it[KEY_LAST_KNOWN_IMSI] = imsi }
    }

    suspend fun setSimSwapConfirmed(confirmed: Boolean) {
        context.dataStore.edit { it[KEY_SIM_SWAP_CONFIRMED] = confirmed }
    }

    suspend fun setSimSwapUnconfirmed(unconfirmed: Boolean) {
        context.dataStore.edit { it[KEY_SIM_SWAP_UNCONFIRMED] = unconfirmed }
    }

    suspend fun setSensitiveAppsGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SENSITIVE_APPS_GUARD_ENABLED] = enabled }
    }

    // Shoulder Surfer
    val isShoulderSurferEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHOULDER_SURFER_ENABLED] ?: false }

    val isAutoIntrusionResponseEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_INTRUSION_RESPONSE] ?: true }
    val isIntrusionAlarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUSION_ALARM] ?: true }
    val isIntrusionPhotoEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUSION_PHOTO] ?: true }
    val isIntrusionSmsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUSION_SMS] ?: true }
    val isIntrusionBlockNetworkEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUSION_BLOCK_NETWORK] ?: true }
    val isIntrusionHoneypotEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTRUSION_HONEYPOT] ?: true }


    suspend fun setShoulderSurferEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOULDER_SURFER_ENABLED] = enabled }
    }

    suspend fun setAutoIntrusionResponseEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_INTRUSION_RESPONSE] = enabled }
    }

    suspend fun setIntrusionAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUSION_ALARM] = enabled }
    }

    suspend fun setIntrusionPhotoEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUSION_PHOTO] = enabled }
    }

    suspend fun setIntrusionSmsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUSION_SMS] = enabled }
    }

    suspend fun setIntrusionBlockNetworkEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUSION_BLOCK_NETWORK] = enabled }
    }

    suspend fun setIntrusionHoneypotEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INTRUSION_HONEYPOT] = enabled }
    }

    // Чек всего телефона
    /** Оценка последней проверки (0..100); `null` — проверок ещё не было. */
    val deviceAuditScore: Flow<Int?> = context.dataStore.data.map { it[KEY_DEVICE_AUDIT_SCORE] }
    val deviceAuditIssues: Flow<Int?> = context.dataStore.data.map { it[KEY_DEVICE_AUDIT_ISSUES] }
    val deviceAuditTime: Flow<Long?> = context.dataStore.data.map { it[KEY_DEVICE_AUDIT_TIME] }

    suspend fun saveDeviceAudit(score: Int, issues: Int, timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit {
            it[KEY_DEVICE_AUDIT_SCORE] = score
            it[KEY_DEVICE_AUDIT_ISSUES] = issues
            it[KEY_DEVICE_AUDIT_TIME] = timestamp
        }
    }

    // Threat intelligence (opt-in)
    val isThreatIntelEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_THREAT_INTEL_ENABLED] ?: false }

    suspend fun setThreatIntelEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_THREAT_INTEL_ENABLED] = enabled }
    }

    /**
     * Засевалась ли база спам-номеров.
     *
     * Раньше сид выполнялся при каждой пустой таблице: пользователь удалял записи,
     * а после перезапуска они возвращались.
     */
    val isSpamDatabaseSeeded: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SPAM_DB_SEEDED] ?: false }

    suspend fun setSpamDatabaseSeeded() {
        context.dataStore.edit { it[KEY_SPAM_DB_SEEDED] = true }
    }

    /** Сброс флага: после «Удалить все данные» база засевается заново. */
    suspend fun resetSpamDatabaseSeeded() {
        context.dataStore.edit { it[KEY_SPAM_DB_SEEDED] = false }
    }

    // Theme
    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { it[KEY_DARK_THEME] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_THEME] = enabled }
    }

    // Language
    private val KEY_LANGUAGE = stringPreferencesKey("app_language")
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: defaultLanguage() }

    suspend fun setLanguage(lang: String) {
        saveLanguageSync(lang)
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    /**
     * Синхронная запись выбранного языка в SharedPreferences-зеркало.
     *
     * Нужна, чтобы `Activity.attachBaseContext()` (и последующий `recreate()`)
     * увидели новый язык сразу: DataStore — асинхронный.
     */
    fun saveLanguageSync(lang: String) {
        context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCALE_KEY, lang)
            .apply()
    }

    // Onboarding
    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { it[KEY_FIRST_LAUNCH] ?: true }
    val isConsentDone: Flow<Boolean> = context.dataStore.data.map { it[KEY_CONSENT_DONE] ?: false }

    suspend fun setFirstLaunchDone() {
        context.dataStore.edit { it[KEY_FIRST_LAUNCH] = false }
    }

    suspend fun setConsentDone() {
        context.dataStore.edit { it[KEY_CONSENT_DONE] = true }
    }

    // Scheduled scan
    val isScheduledScanEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SCHEDULED_SCAN_ENABLED] ?: false }

    suspend fun setScheduledScanEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SCHEDULED_SCAN_ENABLED] = enabled }
    }
}
