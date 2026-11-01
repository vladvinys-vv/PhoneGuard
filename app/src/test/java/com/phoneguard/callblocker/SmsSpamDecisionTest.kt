package com.phoneguard.callblocker

import com.phoneguard.callblocker.SmsSpamDecision.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Решение по входящему SMS.
 *
 * Ключевые инварианты: белый список сильнее всего, отмена доставки — только при
 * блокировке по номеру, а подозрительная ссылка без блокировки номера всё равно
 * даёт предупреждение.
 */
class SmsSpamDecisionTest {

    @Test
    fun cleanMessageIsIgnored() {
        assertEquals(Verdict.IGNORE, SmsSpamDecision.decide(SmsSpamDecision.Facts()))
    }

    @Test
    fun whitelistWinsOverBlacklistAndHeuristics() {
        assertEquals(
            Verdict.IGNORE,
            SmsSpamDecision.decide(
                SmsSpamDecision.Facts(
                    whitelisted = true,
                    blacklisted = true,
                    spamDatabase = true,
                    suspiciousLink = true
                )
            )
        )
    }

    @Test
    fun blacklistedNumberIsBlocked() {
        assertEquals(
            Verdict.BLOCK_NUMBER,
            SmsSpamDecision.decide(SmsSpamDecision.Facts(blacklisted = true))
        )
    }

    @Test
    fun spamDatabaseBlocksNumberToo() {
        assertEquals(
            Verdict.BLOCK_NUMBER,
            SmsSpamDecision.decide(SmsSpamDecision.Facts(spamDatabase = true))
        )
    }

    @Test
    fun suspiciousLinkOnlyWarns() {
        assertEquals(
            "эвристика фишинга не должна отменять доставку",
            Verdict.NOTIFY_SUSPICIOUS,
            SmsSpamDecision.decide(SmsSpamDecision.Facts(suspiciousLink = true))
        )
    }

    @Test
    fun blockedNumberWithLinkStaysNumberBlock() {
        assertEquals(
            Verdict.BLOCK_NUMBER,
            SmsSpamDecision.decide(
                SmsSpamDecision.Facts(blacklisted = true, suspiciousLink = true)
            )
        )
    }

    @Test
    fun onlyNumberBasedBlockAllowsAbort() {
        // Отменять broadcast разрешено только при вердикте BLOCK_NUMBER.
        val verdicts = listOf(
            SmsSpamDecision.Facts(),
            SmsSpamDecision.Facts(suspiciousLink = true),
            SmsSpamDecision.Facts(blacklisted = true),
            SmsSpamDecision.Facts(spamDatabase = true),
            SmsSpamDecision.Facts(whitelisted = true, blacklisted = true)
        ).map(SmsSpamDecision::decide)
        assertEquals(
            setOf(Verdict.IGNORE, Verdict.NOTIFY_SUSPICIOUS, Verdict.BLOCK_NUMBER),
            verdicts.toSet()
        )
        // Два случая блокировки по номеру (чёрный список и спам-база), остальные — нет.
        assertEquals(2, verdicts.count { it == Verdict.BLOCK_NUMBER })
        assertEquals(1, verdicts.count { it == Verdict.NOTIFY_SUSPICIOUS })
    }
}
