package com.phoneguard.data.local

import androidx.room.TypeConverter
import com.phoneguard.model.FirewallLog
import com.phoneguard.model.VaultItemCategory
import com.phoneguard.intrusion.IntrusionSeverity
import com.phoneguard.intrusion.IntrusionType

class Converters {
    @TypeConverter
    fun fromVaultItemCategory(value: VaultItemCategory): String = value.name

    @TypeConverter
    fun toVaultItemCategory(value: String): VaultItemCategory =
        VaultItemCategory.valueOf(value.ifEmpty { VaultItemCategory.OTHER.name })

    @TypeConverter
    fun fromConnectionType(value: FirewallLog.ConnectionType): String = value.name

    @TypeConverter
    fun toConnectionType(value: String): FirewallLog.ConnectionType =
        FirewallLog.ConnectionType.valueOf(value.ifEmpty { FirewallLog.ConnectionType.UNKNOWN.name })

    @TypeConverter
    fun fromTrafficDirection(value: FirewallLog.TrafficDirection): String = value.name

    @TypeConverter
    fun toTrafficDirection(value: String): FirewallLog.TrafficDirection =
        FirewallLog.TrafficDirection.valueOf(value.ifEmpty { FirewallLog.TrafficDirection.UNKNOWN.name })

    @TypeConverter
    fun fromIntrusionSeverity(value: IntrusionSeverity): String = value.name

    @TypeConverter
    fun toIntrusionSeverity(value: String): IntrusionSeverity =
        IntrusionSeverity.valueOf(value.ifEmpty { IntrusionSeverity.LOW.name })

    @TypeConverter
    fun fromIntrusionType(value: IntrusionType): String = value.name

    @TypeConverter
    fun toIntrusionType(value: String): IntrusionType =
        IntrusionType.valueOf(value.ifEmpty { IntrusionType.UNKNOWN.name })

}
