package com.phoneguard.data.local

import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItemCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Конвертеры Room должны переживать старые строки в базе.
 *
 * `valueOf` на неизвестном имени бросает `IllegalArgumentException`, и конвертер падает на любом
 * `SELECT` по таблице — то есть после переименования значения enum приложение умирало бы при
 * чтении истории. Проверяем и обратное направление: имя в базе — ровно `name` константы.
 */
class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `vault category survives a round trip`() {
        VaultItemCategory.entries.forEach { category ->
            assertEquals(category, converters.toVaultItemCategory(converters.fromVaultItemCategory(category)))
        }
    }

    @Test
    fun `unknown vault category falls back to OTHER`() {
        assertEquals(VaultItemCategory.OTHER, converters.toVaultItemCategory("AUDIO_FROM_THE_FUTURE"))
    }

    @Test
    fun `empty vault category falls back to OTHER`() {
        assertEquals(VaultItemCategory.OTHER, converters.toVaultItemCategory(""))
    }

    @Test
    fun `vault category names are stored verbatim`() {
        assertEquals("IMAGE", converters.fromVaultItemCategory(VaultItemCategory.IMAGE))
        assertEquals("DOCUMENT", converters.fromVaultItemCategory(VaultItemCategory.DOCUMENT))
    }

    @Test
    fun `connection type survives a round trip`() {
        FirewallLog.ConnectionType.entries.forEach { type ->
            assertEquals(type, converters.toConnectionType(converters.fromConnectionType(type)))
        }
    }

    @Test
    fun `unknown connection type falls back to UNKNOWN`() {
        assertEquals(FirewallLog.ConnectionType.UNKNOWN, converters.toConnectionType("BLUETOOTH"))
    }

    @Test
    fun `traffic direction survives a round trip`() {
        FirewallLog.TrafficDirection.entries.forEach { direction ->
            assertEquals(direction, converters.toTrafficDirection(converters.fromTrafficDirection(direction)))
        }
    }

    @Test
    fun `unknown traffic direction falls back to UNKNOWN`() {
        assertEquals(FirewallLog.TrafficDirection.UNKNOWN, converters.toTrafficDirection("SIDEWAYS"))
    }

    @Test
    fun `intrusion severity survives a round trip`() {
        IntrusionSeverity.entries.forEach { severity ->
            assertEquals(severity, converters.toIntrusionSeverity(converters.fromIntrusionSeverity(severity)))
        }
    }

    @Test
    fun `unknown intrusion severity falls back to LOW`() {
        assertEquals(IntrusionSeverity.LOW, converters.toIntrusionSeverity("APOCALYPTIC"))
    }

    @Test
    fun `intrusion type survives a round trip`() {
        IntrusionType.entries.forEach { type ->
            assertEquals(type, converters.toIntrusionType(converters.fromIntrusionType(type)))
        }
    }

    @Test
    fun `unknown intrusion type falls back to UNKNOWN`() {
        assertEquals(IntrusionType.UNKNOWN, converters.toIntrusionType("MIND_CONTROL"))
    }

    @Test
    fun `lookup is case sensitive and does not guess`() {
        // Значения в базе пишутся ровно как name константы; "image" — это уже повреждённые данные,
        // и трактовать их как IMAGE значило бы скрыть проблему.
        assertEquals(VaultItemCategory.OTHER, converters.toVaultItemCategory("image"))
        assertEquals(IntrusionSeverity.LOW, converters.toIntrusionSeverity("high"))
    }

    @Test
    fun `whitespace around a value is not trimmed silently`() {
        assertEquals(VaultItemCategory.OTHER, converters.toVaultItemCategory(" IMAGE"))
        assertEquals(FirewallLog.ConnectionType.UNKNOWN, converters.toConnectionType("WIFI "))
    }
}
