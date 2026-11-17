package com.lapisserve.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.lapisserve.app.engine.DeviceHealth
import com.lapisserve.app.system.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Battery, thermal and free-memory state of the phone, refreshed every few seconds. */
@Composable
fun rememberDeviceHealth(everyMs: Long = 6000): State<DeviceHealth> {
    val ctx = LocalContext.current
    return produceState(DeviceHealth()) {
        while (true) {
            value = withContext(Dispatchers.Default) {
                val d = SystemInfo.device(ctx); val m = SystemInfo.memory(ctx)
                DeviceHealth(d.batteryPct, d.charging, d.thermal, m.freeNowMb, m.totalMb)
            }
            delay(everyMs)
        }
    }
}

fun formatUptime(sec: Long): String = when {
    sec >= 86_400 -> "${sec / 86_400}d ${sec % 86_400 / 3600}h"
    sec >= 3600 -> "${sec / 3600}h ${sec % 3600 / 60}m"
    sec >= 60 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec}s"
}
