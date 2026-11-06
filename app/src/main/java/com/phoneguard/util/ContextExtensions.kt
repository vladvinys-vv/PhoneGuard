package com.phoneguard.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Разворачивает обёртки `Context` (в Compose это `ContextThemeWrapper`), чтобы добраться до
 * `Activity`.
 *
 * Нужно там, где система спрашивает именно Activity: `shouldShowRequestPermissionRationale`
 * (без неё «отказано навсегда» неотличимо от «ещё не спрашивали») и `recreate()` при смене
 * языка.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
