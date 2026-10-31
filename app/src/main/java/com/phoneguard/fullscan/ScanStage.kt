package com.phoneguard.fullscan

import androidx.annotation.StringRes
import com.phoneguard.R

/**
 * Этапы полного сканирования.
 *
 * Раньше текст этапа был жёстко зашит по-русски прямо в оркестраторе, поэтому
 * прогресс не переводился. Теперь оркестратор отдаёт [ScanStage], а экран
 * подставляет строку из ресурсов.
 */
enum class ScanStage(@StringRes val titleRes: Int) {
    ROOT(R.string.scan_stage_root),
    INTEGRITY(R.string.scan_stage_integrity),
    FILE_SYSTEM(R.string.scan_stage_files),
    DEVELOPER_OPTIONS(R.string.scan_stage_dev_options),
    SECURITY_PATCH(R.string.scan_stage_patch),
    ACCESSIBILITY(R.string.scan_stage_accessibility),
    OVERLAYS(R.string.scan_stage_overlay),
    PERMISSIONS(R.string.scan_stage_permissions),
    HIDDEN_APPS(R.string.scan_stage_hidden_apps),
    DEVICE_ADMINS(R.string.scan_stage_device_admin),
    PRIVACY_APPS(R.string.scan_stage_privacy_apps),
    SPYWARE(R.string.scan_stage_spyware),
    FIREWALL(R.string.scan_stage_firewall),
    APK_SIGNATURES(R.string.scan_stage_apk_signatures),
    PHISHING(R.string.scan_stage_phishing),
    PHONE_CHECK(R.string.scan_stage_phone_check),
    FINISHED(R.string.scan_stage_done),
    ALREADY_RUNNING(R.string.scan_stage_already_running)
}
