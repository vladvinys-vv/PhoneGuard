package com.phoneguard.spywarecheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.phoneguard.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PackageAddedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageAddedReceiver"
        private const val CHANNEL_ID = "package_added_channel"
        private const val NOTIFICATION_ID = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        Log.d(TAG, "New package installed: $packageName")

        createNotificationChannel(context)

        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            null
        } ?: return

        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        if (!isSystemApp) {
            showNotification(context, pm.getApplicationLabel(appInfo).toString(), packageName)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Новые приложения",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context, appName: String, packageName: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Установлено новое приложение")
            .setContentText(appName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
