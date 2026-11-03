package com.phoneguard.util

import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context
) {
    /**
     * Срок жизни кэша и его содержимое — в [TtlCache]: там значение и метка времени
     * публикуются одной ссылкой, поэтому читатель не увидит «новый список со старой меткой».
     */
    private val cache = TtlCache<List<ApplicationInfo>>(CACHE_VALIDITY_MS)

    suspend fun getInstalledApps(): List<ApplicationInfo> = withContext(Dispatchers.IO) {
        cache.getOrNull()?.let { return@withContext it }

        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        cache.put(apps)
        apps
    }

    fun invalidateCache() {
        cache.invalidate()
    }

    private companion object {
        val CACHE_VALIDITY_MS = TimeUnit.HOURS.toMillis(24)
    }
}
