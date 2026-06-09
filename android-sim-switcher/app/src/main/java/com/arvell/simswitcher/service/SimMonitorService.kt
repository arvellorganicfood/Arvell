package com.arvell.simswitcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arvell.simswitcher.R
import com.arvell.simswitcher.core.SimInfoProvider
import com.arvell.simswitcher.core.SwitchDecisionEngine
import com.arvell.simswitcher.data.SettingsRepository
import com.arvell.simswitcher.model.SwitchConfig
import com.arvell.simswitcher.model.SwitchDecision
import com.arvell.simswitcher.monitor.NetworkQualityMonitor
import com.arvell.simswitcher.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.app.PendingIntent

/**
 * Long-running foreground service that ties everything together:
 * monitor → decision engine → switch request. It re-evaluates on a fixed
 * cadence (and whenever quality changes), actively probing reachability so a
 * silently dead SIM is still caught.
 */
class SimMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var simInfo: SimInfoProvider
    private lateinit var settings: SettingsRepository
    private lateinit var monitor: NetworkQualityMonitor
    private val engine = SwitchDecisionEngine()

    override fun onCreate() {
        super.onCreate()
        simInfo = SimInfoProvider(this)
        settings = SettingsRepository(this)
        monitor = NetworkQualityMonitor(this, simInfo, scope)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        engine.reset()
        monitor.start()
        startDecisionLoop()
        observeSwitchResults()
        return START_STICKY
    }

    private fun startDecisionLoop() = scope.launch {
        while (isActive) {
            monitor.probeReachability()
            val config = settings.config.first()
            if (config.enabled) {
                evaluateOnce(config)
            }
            delay(EVAL_INTERVAL_MS)
        }
    }

    private suspend fun evaluateOnce(config: SwitchConfig) {
        val quality = monitor.quality.value.copy(activeDataSubId = simInfo.activeDataSubId())
        val available = simInfo.activeSims().map { it.subscriptionId }
        if (available.size < 2) return // nothing to switch to

        when (val decision = engine.evaluate(quality, available, config)) {
            is SwitchDecision.Switch -> {
                if (!SwitchRequestBus.accessibilityConnected) {
                    status.value = "Switch needed but accessibility service is off"
                    return
                }
                val target = simInfo.findById(decision.targetSubId)
                status.value = "Switching to ${target?.label ?: decision.targetSubId}: ${decision.reason}"
                SwitchRequestBus.requestSwitch(
                    SwitchRequestBus.SwitchRequest(
                        targetSubId = decision.targetSubId,
                        targetSlotIndex = target?.slotIndex ?: 0,
                        targetLabel = target?.label ?: "SIM",
                        reason = decision.reason,
                    ),
                )
            }
            SwitchDecision.Hold -> Unit
        }
    }

    private fun observeSwitchResults() = scope.launch {
        SwitchRequestBus.results.collect { result ->
            status.value = if (result.success) {
                "Switched data SIM successfully"
            } else {
                "Switch failed: ${result.detail}"
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder system icon
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val mgr = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_desc) }
        mgr.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        monitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sim_monitor"
        private const val NOTIF_ID = 42
        private const val EVAL_INTERVAL_MS = 5_000L

        /** Last human-readable status, surfaced by the UI. */
        val status = MutableStateFlow("Idle")

        fun start(context: Context) {
            val intent = Intent(context, SimMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SimMonitorService::class.java))
        }
    }
}
