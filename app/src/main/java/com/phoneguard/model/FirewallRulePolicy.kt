package com.phoneguard.model

/**
 * Решение «весь трафик приложения заблокирован» — чистая функция без Android и корутин.
 *
 * Вынесена из `FirewallRulesManager` (там она жила рядом с DAO, `PackageManager` и Flow), чтобы
 * матрицу правил можно было прогонять юнит-тестами в любой среде. От этого решения зависит,
 * попадёт ли пакет из `PhoneGuardVpnService` в TUN или уйдёт мимо, — ошибка в любую сторону
 * означает либо утечку трафика, либо «приложение молча не работает».
 *
 * Порядок проверок намеренный: `blockAll` сильнее типа сети, а `allowByDefault = false` — это
 * режим «запрещено всё, что не разрешено явно», поэтому он блокирует независимо от флагов.
 */
fun FirewallRule.blocksAllTraffic(isOnWifi: Boolean): Boolean {
    if (blockAll) return true
    if (!allowByDefault) return true
    if (isOnWifi && blockWifi) return true
    if (!isOnWifi && blockMobile) return true
    if (allowWifiOnly && !isOnWifi) return true
    if (allowMobileOnly && isOnWifi) return true
    return false
}
