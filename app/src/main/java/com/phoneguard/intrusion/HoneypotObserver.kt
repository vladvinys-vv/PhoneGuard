package com.phoneguard.intrusion

import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Наблюдатель за каталогом honeypot: любой доступ к файлам-приманкам означает,
 * что сторонний процесс читает «интересные» для malware файлы.
 *
 * Исправления:
 *  - конструктор `FileObserver(File, mask)` появился только в API 29, поэтому на
 *    Android 8–9 приложение падало бы с `NoSuchMethodError`. Для старых версий
 *    используется конструктор с путём;
 *  - `path` в `onEvent` относителен каталога — теперь наружу отдаётся полный путь,
 *    иначе `HoneypotManager.isDecoyAccess()` не находил имена приманок;
 *  - ошибки наблюдения логируются, а не проглатываются.
 */
class HoneypotObserver(
    private val directory: File,
    private val onAccess: (String) -> Unit
) {

    private var observer: FileObserver? = null

    fun start() {
        if (observer != null) return
        if (!directory.exists()) directory.mkdirs()

        val mask = FileObserver.ACCESS or FileObserver.OPEN or FileObserver.MODIFY or FileObserver.DELETE
        val callback: (Int, String?) -> Unit = { event, path ->
            if (path != null) {
                val absolute = File(directory, path).absolutePath
                Log.d(TAG, "honeypot event=$event path=$absolute")
                onAccess(absolute)
            }
        }

        observer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DirFileObserver(directory, mask, callback)
            } else {
                @Suppress("DEPRECATION")
                LegacyFileObserver(directory.absolutePath, mask, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start honeypot observer", e)
            null
        }

        observer?.startWatching()
    }

    fun stop() {
        runCatching { observer?.stopWatching() }
        observer = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class DirFileObserver(
        directory: File,
        mask: Int,
        private val callback: (Int, String?) -> Unit
    ) : FileObserver(directory, mask) {
        override fun onEvent(event: Int, path: String?) = callback(event, path)
    }

    private class LegacyFileObserver(
        path: String,
        mask: Int,
        private val callback: (Int, String?) -> Unit
    ) : FileObserver(path, mask) {
        override fun onEvent(event: Int, path: String?) = callback(event, path)
    }

    private companion object {
        const val TAG = "HoneypotObserver"
    }
}
