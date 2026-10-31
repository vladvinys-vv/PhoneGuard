package com.phoneguard.intrusion

import com.phoneguard.data.local.IntrusionEventEntity

fun IntrusionEventEntity.toIntrusionEvent(): IntrusionEvent =
    IntrusionEvent(
        id = id,
        timestamp = timestamp,
        type = type,
        severity = severity,
        evidence = evidence.split(";").filter { it.isNotBlank() }.associate {
            val parts = it.split("=", limit = 2)
            parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
        },
        uid = uid,
        packageName = packageName,
        ipAddress = ipAddress,
        photoPath = photoPath,
        handled = handled
    )
