package com.phoneguard.util

import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await

suspend fun FusedLocationProviderClient.await(): android.location.Location? = try {
    kotlinx.coroutines.tasks.await(lastLocation)
} catch (e: Exception) {
    null
}
