package com.phoneguard.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppsCache @Inject constructor(
    private val context: Context
) {
    private var cachedApps: List<ApplicationInfo>? = null
    private var cacheTimestamp: Long = 0
    private val cacheValidityMs = TimeUnit.HOURS.toMillis(24)

    suspend fun getInstalledApps(): List<ApplicationInfo> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedApps != null && (now - cacheTimestamp) < cacheValidityMs) {
            return@withContext cachedApps!!
        }

        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        cachedApps = apps
        cacheTimestamp = now
        apps
    }

    fun invalidateCache() {
        cachedApps = null
        cacheTimestamp = 0
    }
}