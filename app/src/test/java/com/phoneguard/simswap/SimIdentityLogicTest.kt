package com.phoneguard.simswap

import com.phoneguard.simswap.SimIdentityLogic.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Логика сравнения отпечатков SIM: детектор подмены обязан (1) запоминать базовый
 * отпечаток на первом запуске и (2) не затирать его, когда идентификатор недоступен.
 */
class SimIdentityLogicTest {

    @Test
    fun firstRunSeedsBaseline() {
        assertEquals(Decision.SEED_BASELINE, SimIdentityLogic.decide(null, "9f86d081"))
    }

    @Test
    fun missingCurrentIdentityIsIgnored() {
        assertEquals(Decision.IGNORE, SimIdentityLogic.decide("sha256:9f86d081", null))
        assertEquals(Decision.IGNORE, SimIdentityLogic.decide(null, null))
    }

    @Test
    fun legacyPlainValueSeedsBaselineInsteadOfAlerting() {
        // Раньше в SharedPreferences лежал IMSI в открытом виде — это миграция, не подмена.
        assertEquals(
            Decision.SEED_BASELINE,
            SimIdentityLogic.decide("310150123456789", "9f86d081")
        )
        assertEquals(Decision.SEED_BASELINE, SimIdentityLogic.decide("", "9f86d081"))
    }

    @Test
    fun unchangedFingerprintIsIgnored() {
        assertEquals(Decision.IGNORE, SimIdentityLogic.decide("sha256:9f86d081", "9f86d081"))
    }

    @Test
    fun changedFingerprintIsSwap() {
        assertEquals(Decision.SWAPPED, SimIdentityLogic.decide("sha256:9f86d081", "2c26b46b"))
    }

    @Test
    fun prefixOnlyMatchesOnlyWhenHashesAreEqual() {
        // Отпечаток из одного префикса без значения — битые данные, но не паника:
        // пустой хеш не совпадёт с настоящим, значит фиксируем подмену.
        assertEquals(Decision.SWAPPED, SimIdentityLogic.decide("sha256:", "9f86d081"))
    }
}
