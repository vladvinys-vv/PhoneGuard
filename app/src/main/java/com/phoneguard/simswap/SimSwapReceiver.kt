package com.phoneguard.simswap

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SimSwapReceiver : BroadcastReceiver() {

    @Inject
    lateinit var detector: SimSwapDetector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_SIM_STATE_CHANGED) return

        scope.launch {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }

            val state = telephonyManager.simState
            if (state == TelephonyManager.SIM_STATE_ABSENT ||
                state == TelephonyManager.SIM_STATE_READY
            ) {
                if (!detector.hasUnconfirmed()) {
                    detector.onSimStateChanged()
                }
            }
        }
    }
}