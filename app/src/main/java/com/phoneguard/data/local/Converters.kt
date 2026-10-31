package com.phoneguard.data.local

import androidx.room.TypeConverter
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItemCategory

/**
 * Конвертеры enum-полей Room.
 *
 * `valueOf` бросает `IllegalArgumentException` на неизвестном имени — это значит, что
 * после переименования/удаления значения enum чтение старых строк роняло приложение
 * (падение в конвертере происходит при любом SELECT по таблице). Теперь неизвестное
 * значение трактуется как «неизвестно», а не как ошибка.
 */
class Converters {
    @TypeConverter
    fun fromVaultItemCategory(value: VaultItemCategory): String = value.name

    @TypeConverter
    fun toVaultItemCategory(value: String): VaultItemCategory =
        enumValueOr(value, VaultItemCategory.OTHER)

    @TypeConverter
    fun fromConnectionType(value: FirewallLog.ConnectionType): String = value.name

    @TypeConverter
    fun toConnectionType(value: String): FirewallLog.ConnectionType =
        enumValueOr(value, FirewallLog.ConnectionType.UNKNOWN)

    @TypeConverter
    fun fromTrafficDirection(value: FirewallLog.TrafficDirection): String = value.name

    @TypeConverter
    fun toTrafficDirection(value: String): FirewallLog.TrafficDirection =
        enumValueOr(value, FirewallLog.TrafficDirection.UNKNOWN)

    @TypeConverter
    fun fromIntrusionSeverity(value: IntrusionSeverity): String = value.name

    @TypeConverter
    fun toIntrusionSeverity(value: String): IntrusionSeverity =
        enumValueOr(value, IntrusionSeverity.LOW)

    @TypeConverter
    fun fromIntrusionType(value: IntrusionType): String = value.name

    @TypeConverter
    fun toIntrusionType(value: String): IntrusionType =
        enumValueOr(value, IntrusionType.UNKNOWN)

    private inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
