package com.phoneguard.util

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Сколько ждём последнюю известную точку, если провайдер не отвечает. */
private const val LAST_LOCATION_TIMEOUT_MS = 3_000L

/**
 * Последняя известная точка с **ограничением ожидания**.
 *
 * `Task.await()` завершается только когда FusedLocationProvider пришлёт результат:
 * без сети и без свежего фикса задача может не завершиться никогда, и вызывающий код
 * (например, обработка подмены SIM) зависал навсегда — до записи события и показа
 * подтверждения дело не доходило.
 */
suspend fun FusedLocationProviderClient.awaitLastLocationOrNull(
    timeoutMs: Long = LAST_LOCATION_TIMEOUT_MS
): Location? = withTimeoutOrNull(timeoutMs) {
    runCatching { lastLocation.await() }.getOrNull()
}
