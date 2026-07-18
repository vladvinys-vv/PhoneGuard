package com.phoneguard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.phoneguard.util.SecurityUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "phoneguard_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_PIN_HASH = stringPreferencesKey("pin_code_hash")
        private val KEY_BACKUP_NUMBER = stringPreferencesKey("backup_number")
        private val KEY_SIM_LOCK_ENABLED = booleanPreferencesKey("sim_lock_enabled")
        private val KEY_DEVICE_ADMIN_ENABLED = booleanPreferencesKey("device_admin_enabled")
        private val KEY_PHOTO_ON_FAILED_ATTEMPTS = booleanPreferencesKey("photo_on_failed_attempts")
        private val KEY_REMOTE_ALARM_ENABLED = booleanPreferencesKey("remote_alarm_enabled")
        private val KEY_IS_PRO = booleanPreferencesKey("is_pro")
        private val KEY_LAST_SCAN_TIME = longPreferencesKey("last_scan_time")
        private val KEY_BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown")
        private val KEY_BLOCK_HIDDEN = booleanPreferencesKey("block_hidden")
        private val KEY_BLOCK_INTERNATIONAL = booleanPreferencesKey("block_international")

        // SimSwap
        private val KEY_LAST_KNOWN_IMSI = stringPreferencesKey("last_known_imsi")
        private val KEY_SIM_SWAP_CONFIRMED = booleanPreferencesKey("sim_swap_confirmed")
        private val KEY_SIM_SWAP_UNCONFIRMED = booleanPreferencesKey("sim_swap_unconfirmed")
        private val KEY_SENSITIVE_APPS_GUARD_ENABLED = booleanPreferencesKey("sensitive_apps_guard_enabled")

        // Shoulder Surfer
        private val KEY_SHOULDER_SURFER_ENABLED = booleanPreferencesKey("shoulder_surfer_enabled")

        // Theme
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")

        // Onboarding
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
    }

    // PIN Hash
    val pinCodeHash: Flow<String?> = context.dataStore.data.map { it[KEY_PIN_HASH] }
    val hasPinSet: Flow<Boolean> = pinCodeHash.map { it != null }

    suspend fun savePinCode(pin: String) {
        val hashedPin = SecurityUtils.hashPin(pin)
        context.dataStore.edit { it[KEY_PIN_HASH] = hashedPin }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val storedHash = context.dataStore.data.map { it[KEY_PIN_HASH] }.take(1).first()
        return storedHash?.let { SecurityUtils.verifyPin(pin, it) } ?: false
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
    // TODO: re-enable if publishing to Play with monetization
    val isPro: Flow<Boolean> = flow { emit(true) }

    suspend fun setPro(isPro: Boolean) {
        // No-op, pro is always enabled for personal use
        // context.dataStore.edit { it[KEY_IS_PRO] = isPro }
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

    suspend fun setShoulderSurferEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOULDER_SURFER_ENABLED] = enabled }
    }

    // Theme
    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { it[KEY_DARK_THEME] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DARK_THEME] = enabled }
    }

    // Onboarding
    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { it[KEY_FIRST_LAUNCH] ?: true }

    suspend fun setFirstLaunchDone() {
        context.dataStore.edit { it[KEY_FIRST_LAUNCH] = false }
    }
}
