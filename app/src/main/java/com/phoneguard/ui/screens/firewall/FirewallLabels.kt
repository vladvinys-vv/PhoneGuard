package com.phoneguard.ui.screens.firewall

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.phoneguard.R
import com.phoneguard.model.FirewallLog

/**
 * Подписи для журнала брандмауэра.
 *
 * Раньше экраны печатали `connectionType.name` / `trafficDirection.name`, то есть
 * пользователь видел «OUTBOUND • WIFI» на любом языке, а заголовок «Recent Blocks»
 * был захардкожен по-английски. Теперь всё через ресурсы.
 */
@Composable
fun connectionTypeLabel(type: FirewallLog.ConnectionType): String = stringResource(
    when (type) {
        FirewallLog.ConnectionType.WIFI -> R.string.connection_type_wifi
        FirewallLog.ConnectionType.MOBILE -> R.string.connection_type_mobile
        FirewallLog.ConnectionType.UNKNOWN -> R.string.connection_type_unknown
    }
)

@Composable
fun trafficDirectionLabel(direction: FirewallLog.TrafficDirection): String = stringResource(
    when (direction) {
        FirewallLog.TrafficDirection.INBOUND -> R.string.traffic_direction_inbound
        FirewallLog.TrafficDirection.OUTBOUND -> R.string.traffic_direction_outbound
        FirewallLog.TrafficDirection.UNKNOWN -> R.string.traffic_direction_unknown
    }
)
