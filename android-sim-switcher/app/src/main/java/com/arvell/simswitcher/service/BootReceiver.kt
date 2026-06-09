package com.arvell.simswitcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arvell.simswitcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts monitoring after reboot, but only if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = SettingsRepository(context).config.first().enabled
                if (enabled) SimMonitorService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
