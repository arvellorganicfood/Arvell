package com.arvell.simswitcher.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.arvell.simswitcher.core.SimInfoProvider
import com.arvell.simswitcher.model.NetworkQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Continuously observes the quality of the *cellular* data connection on the
 * active SIM and emits [NetworkQuality] snapshots.
 *
 * Three independent signals are combined:
 *  1. Connectivity validation — [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
 *     on a CELLULAR transport. This is the platform's own "does this network
 *     reach the internet" verdict.
 *  2. Signal strength — via [TelephonyCallback.SignalStrengthsListener] (API 31+).
 *  3. Active reachability — a lightweight HTTP probe bound to the cellular
 *     network, run on demand by the service loop ([probeReachability]).
 */
class NetworkQualityMonitor(
    private val context: Context,
    private val simInfo: SimInfoProvider,
    private val scope: CoroutineScope,
) {
    private val connectivity = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
    private val telephony = ContextCompat.getSystemService(context, TelephonyManager::class.java)

    private val _quality = MutableStateFlow(NetworkQuality(activeDataSubId = simInfo.activeDataSubId()))
    val quality: StateFlow<NetworkQuality> = _quality.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyCallback: TelephonyCallback? = null

    fun start() {
        registerConnectivityCallback()
        registerSignalCallback()
    }

    fun stop() {
        networkCallback?.let { connectivity?.unregisterNetworkCallback(it) }
        networkCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { telephony?.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
    }

    private fun registerConnectivityCallback() {
        val cm = connectivity ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _quality.update { it.copy(hasValidatedInternet = validated, activeDataSubId = simInfo.activeDataSubId()) }
            }

            override fun onLost(network: Network) {
                _quality.update { it.copy(hasValidatedInternet = false, reachable = false) }
            }
        }
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun registerSignalCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // pre-31 uses deprecated listener; omitted for brevity
        val tm = telephony ?: return
        val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                _quality.update { it.copy(signalLevel = signalStrength.level) }
            }
        }
        tm.registerTelephonyCallback(context.mainExecutor, cb)
        telephonyCallback = cb
    }

    /**
     * Actively probe internet reachability over the cellular network and fold the
     * result into the current snapshot. Cheap (HTTP 204), bound to the cellular
     * network so Wi-Fi cannot mask a dead SIM. Safe to call from the service loop.
     */
    fun probeReachability() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { doProbe() }
            _quality.update { it.copy(reachable = ok) }
        }
    }

    private fun doProbe(): Boolean {
        val cm = connectivity ?: return false
        val cellular = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } ?: return false
        return try {
            val conn = cellular.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.useCaches = false
            try {
                conn.responseCode == 204
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        // Standard captive-portal/connectivity probe endpoint returning HTTP 204.
        const val PROBE_URL = "https://clients3.google.com/generate_204"
        const val PROBE_TIMEOUT_MS = 3_000
    }
}
