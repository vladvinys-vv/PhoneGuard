package com.phoneguard.callblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок правил блокировки входящего звонка.
 *
 * Здесь важна именно последовательность: белый список должен побеждать чёрный
 * (иначе пользователь теряет звонки, которые сам разрешил), а скрытый номер
 * решается двумя настройками сразу.
 */
class CallBlockDecisionTest {

    private val parsed = CallBlockDecision.Facts(numberParsed = true)

    @Test
    fun plainNumberIsNotBlockedWithoutSettings() {
        assertFalse(CallBlockDecision.shouldBlock(parsed))
    }

    @Test
    fun whitelistWinsOverEverything() {
        assertFalse(
            CallBlockDecision.shouldBlock(
                parsed.copy(
                    whitelisted = true,
                    blacklisted = true,
                    spamDatabase = true,
                    blockUnknown = true,
                    blockHidden = true,
                    blockInternational = true,
                    foreignNumber = true
                )
            )
        )
    }

    @Test
    fun blacklistBlocksWithoutAnySetting() {
        assertTrue(CallBlockDecision.shouldBlock(parsed.copy(blacklisted = true)))
    }

    @Test
    fun spamDatabaseBlocksWithoutAnySetting() {
        assertTrue(CallBlockDecision.shouldBlock(parsed.copy(spamDatabase = true)))
    }

    @Test
    fun hiddenCallFollowsHiddenAndUnknownSettings() {
        val hidden = CallBlockDecision.Facts(callerIdHidden = true, numberParsed = false)
        assertFalse(CallBlockDecision.shouldBlock(hidden))
        assertTrue(CallBlockDecision.shouldBlock(hidden.copy(blockHidden = true)))
        // «Блокировать неизвестные» тоже отсекает скрытые номера — прежнее поведение.
        assertTrue(CallBlockDecision.shouldBlock(hidden.copy(blockUnknown = true)))
    }

    @Test
    fun unparsableNumberFollowsUnknownSettingOnly() {
        val unparsable = CallBlockDecision.Facts(numberParsed = false)
        assertFalse(CallBlockDecision.shouldBlock(unparsable))
        assertTrue(CallBlockDecision.shouldBlock(unparsable.copy(blockUnknown = true)))
        // blockHidden относится только к скрытым номерам.
        assertFalse(CallBlockDecision.shouldBlock(unparsable.copy(blockHidden = true)))
    }

    @Test
    fun internationalCallFollowsItsOwnSetting() {
        val foreign = parsed.copy(foreignNumber = true)
        assertFalse("без настройки международные звонки проходят", CallBlockDecision.shouldBlock(foreign))
        assertTrue(CallBlockDecision.shouldBlock(foreign.copy(blockInternational = true)))
    }

    @Test
    fun ownCountryIsNotBlockedByInternationalSetting() {
        assertFalse(
            CallBlockDecision.shouldBlock(
                parsed.copy(blockInternational = true, foreignNumber = false)
            )
        )
    }

    @Test
    fun hiddenCallIsNotTreatedAsUnparsableTwice() {
        // Скрытый номер: ветка «номер не разобрался» уже не участвует,
        // иначе blockUnknown применялся бы дважды и путался с blockHidden.
        val hiddenWithBlockHidden = CallBlockDecision.Facts(
            callerIdHidden = true,
            numberParsed = true,
            blockHidden = true
        )
        assertTrue(CallBlockDecision.shouldBlock(hiddenWithBlockHidden))
    }
}
