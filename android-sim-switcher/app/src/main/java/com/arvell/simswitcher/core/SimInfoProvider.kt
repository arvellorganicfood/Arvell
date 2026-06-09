package com.arvell.simswitcher.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.arvell.simswitcher.model.SimInfo

/**
 * Thin wrapper over [SubscriptionManager] for reading the SIM list and the
 * current default *data* subscription. All calls require [Manifest.permission.READ_PHONE_STATE];
 * callers must check [hasPhonePermission] first.
 */
class SimInfoProvider(private val context: Context) {

    private val subscriptionManager: SubscriptionManager? =
        ContextCompat.getSystemService(context, SubscriptionManager::class.java)

    fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** All active SIMs capable of carrying data, ordered by slot. */
    fun activeSims(): List<SimInfo> {
        if (!hasPhonePermission()) return emptyList()
        val sm = subscriptionManager ?: return emptyList()
        @Suppress("MissingPermission")
        val list = sm.activeSubscriptionInfoList ?: return emptyList()
        return list
            .sortedBy { it.simSlotIndex }
            .map {
                SimInfo(
                    subscriptionId = it.subscriptionId,
                    slotIndex = it.simSlotIndex,
                    displayName = it.displayName?.toString().orEmpty(),
                    carrierName = it.carrierName?.toString().orEmpty(),
                )
            }
    }

    /** subscriptionId currently set as the default data SIM, or -1 if unknown. */
    fun activeDataSubId(): Int {
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) -1 else id
    }

    fun findById(subId: Int): SimInfo? = activeSims().firstOrNull { it.subscriptionId == subId }

    /** True only on multi-SIM devices where switching is meaningful. */
    fun isDualSim(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return activeSims().size > 1
        return activeSims().size > 1
    }
}
