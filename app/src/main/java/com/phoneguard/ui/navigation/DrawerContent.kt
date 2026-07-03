package com.phoneguard.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneguard.R

/**
 * Структура для описания пункта бокового меню (и любого навигационного элемента).
 * Используется и в DrawerContent, и может быть переиспользована в других UI-компонентах.
 */
data class NavItem(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector
)

/**
 * Все навигационные пункты приложения, отсортированные для Drawer.
 * Включает все 8 экранов.
 */
val drawerNavItems: List<NavItem> = listOf(
    NavItem("dashboard", R.string.dashboard, Icons.Default.Home),
    NavItem("fullscan", R.string.full_scan, Icons.Default.Search),
    NavItem("antitheft", R.string.anti_theft, Icons.Default.Lock),
    NavItem("callblocker", R.string.call_sms_blocker, Icons.Default.Block),
    NavItem("privacy", R.string.privacy_scanner, Icons.Default.VisibilityOff),
    NavItem("spyware", R.string.spyware_check, Icons.Default.Search),
    NavItem("firewall", R.string.firewall, Icons.Default.Security),
    NavItem("vault", R.string.secure_vault, Icons.Default.Lock),
    NavItem("simswap", R.string.sim_swap, Icons.Default.Warning),
    NavItem("settings", R.string.settings, Icons.Default.Settings)
)

@Composable
fun DrawerHeader(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = "PhoneGuard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun DrawerItem(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(
                color = containerColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.titleRes),
            tint = if (isSelected) MaterialTheme.colorScheme.primary else contentColor,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = stringResource(item.titleRes),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor
        )
    }
}

/**
 * Содержимое бокового выдвижного меню (Navigation Drawer).
 *
 * @param currentRoute  текущий активный маршрут — будет подсвечен
 * @param onNavigateTo  коллбэк при выборе пункта меню
 * @param onCloseDrawer коллбэк для закрытия drawer после выбора
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    currentRoute: String,
    onNavigateTo: (String) -> Unit,
    onCloseDrawer: () -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        // Шапка
        DrawerHeader()

        Spacer(modifier = Modifier.height(8.dp))

        // Пункты меню
        drawerNavItems.forEach { item ->
            DrawerItem(
                item = item,
                isSelected = currentRoute == item.route,
                onClick = {
                    onNavigateTo(item.route)
                    onCloseDrawer()
                }
            )
        }
    }
}
