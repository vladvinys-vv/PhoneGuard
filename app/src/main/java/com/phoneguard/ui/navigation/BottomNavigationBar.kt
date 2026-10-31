package com.phoneguard.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.phoneguard.R

/**
 * Описание навигационного пункта (единственный источник правды для нижнего меню).
 *
 * Раньше `NavItem`/`drawerNavItems` были объявлены дважды — в этом файле и в
 * `DrawerContent.kt`, что давало ошибку компиляции «redeclaration». Drawer не
 * использовался (навигация — нижняя панель), поэтому дубликат удалён.
 */
data class NavItem(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector
)

/** Пункты нижней навигации. */
val bottomNavItems: List<NavItem> = listOf(
    NavItem("dashboard", R.string.dashboard, Icons.Default.Home),
    NavItem("antitheft", R.string.anti_theft, Icons.Default.Lock),
    NavItem("callblocker", R.string.call_sms_blocker, Icons.Default.Block),
    NavItem("firewall", R.string.firewall, Icons.Default.Security),
    NavItem("settings", R.string.settings, Icons.Default.Settings)
)

@Composable
fun PhoneGuardBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        bottomNavItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { if (currentRoute != item.route) onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.titleRes)
                    )
                },
                label = { Text(stringResource(item.titleRes)) }
            )
        }
    }
}
