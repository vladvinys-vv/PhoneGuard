package com.phoneguard.callblocker

/**
 * Решение по входящему SMS: игнорировать, предупредить или блокировать номер.
 *
 * Раньше цепочка была выписана прямо в `SmsSpamReceiver` (Android-приёмник с Room,
 * DataStore и `PhishingUrlChecker`), поэтому её нельзя было проверить тестом.
 * Здесь зафиксированы три вещи, которые легко сломать незаметно:
 *  * белый список пользователя всегда побеждает;
 *  * отменять доставку (`abortBroadcast`) можно только при блокировке **по номеру** —
 *    эвристика фишинга слишком грубая, чтобы «съедать» сообщение;
 *  * подозрительная ссылка всё равно должна показать уведомление, даже если номер
 *    не в списках.
 */
internal object SmsSpamDecision {

    /** Факты о конкретном сообщении. */
    data class Facts(
        /** Номер есть в белом списке пользователя. */
        val whitelisted: Boolean = false,
        /** Номер есть в чёрном списке. */
        val blacklisted: Boolean = false,
        /** Номер найден в локальной спам-базе. */
        val spamDatabase: Boolean = false,
        /** В тексте есть ссылка, похожая на фишинг (локальная эвристика). */
        val suspiciousLink: Boolean = false
    )

    enum class Verdict {
        /** Ничего не делаем (в том числе для белого списка). */
        IGNORE,

        /** Показать предупреждение, но доставку не отменять. */
        NOTIFY_SUSPICIOUS,

        /** Номер заблокирован: предупреждение и, если приложение — SMS по умолчанию, отмена доставки. */
        BLOCK_NUMBER
    }

    fun decide(facts: Facts): Verdict = when {
        facts.whitelisted -> Verdict.IGNORE
        facts.blacklisted || facts.spamDatabase -> Verdict.BLOCK_NUMBER
        facts.suspiciousLink -> Verdict.NOTIFY_SUSPICIOUS
        else -> Verdict.IGNORE
    }
}
