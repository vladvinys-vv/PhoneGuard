package com.phoneguard.intrusion

import android.os.FileObserver
import java.io.File

class HoneypotObserver(
    private val directory: File,
    private val onAccess: (String) -> Unit
) {
    private var observer: FileObserver? = null

    fun start() {
        if (!directory.exists()) directory.mkdirs()
        observer = object : FileObserver(directory, ACCESS or MODIFY or OPEN or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) onAccess(path)
            }
        }
        observer?.startWatching()
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }
}
