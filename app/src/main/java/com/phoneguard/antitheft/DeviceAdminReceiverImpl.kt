package com.phoneguard.antitheft

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.phoneguard.R

/**
 * Приёмник статуса Device Admin.
 *
 * Сообщения были захардкожены по-английски, хотя приложение локализовано на четыре
 * языка (RU/EN/RU/DE) — теперь тексты берутся из ресурсов.
 */
class DeviceAdminReceiverImpl : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.device_admin_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.device_admin_disabled, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, DeviceAdminReceiverImpl::class.java)
        }
    }
}
