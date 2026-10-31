package com.phoneguard.intrusion


enum class IntrusionSeverity { LOW, MEDIUM, HIGH, CRITICAL }

enum class IntrusionType {
    RAT_DETECTED,
    HOOKING_DETECTED,
    ANALYSIS_OR_PARSER_ATTEMPT,
    BRUTE_FORCE,
    FIREWALL_BYPASS,

    /** Кто-то открыл файл-приманку (honeypot): признак вредоносного ПО. */
    HONEYPOT_ACCESSED,
    UNKNOWN
}

data class IntrusionSignal(
    val type: IntrusionType,
    val evidence: Map<String, String> = emptyMap(),
    val severity: IntrusionSeverity = IntrusionSeverity.MEDIUM
)

data class IntrusionEvent(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: IntrusionType,
    val severity: IntrusionSeverity,
    val evidence: Map<String, String> = emptyMap(),
    val uid: Int? = null,
    val packageName: String? = null,
    val ipAddress: String? = null,
    val photoPath: String? = null,
    val handled: Boolean = false
) {
    /**
     * Однострочное описание для журнала и уведомлений: `ТИП ВАЖНОСТЬ ключ=значение …`.
     *
     * Улики разделяются пробелом, а не запятой, и при их отсутствии не остаётся хвостового
     * пробела — иначе в логах появляется «BRUTE_FORCE MEDIUM » и grep по `MEDIUM$` не работает.
     */
    fun summary(): String = buildString {
        append(type.name).append(' ').append(severity.name)
        evidence.entries.forEach { entry -> append(' ').append(entry.key).append('=').append(entry.value) }
    }
}
