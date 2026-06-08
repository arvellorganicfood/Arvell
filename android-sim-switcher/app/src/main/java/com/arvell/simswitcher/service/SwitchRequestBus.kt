package com.arvell.simswitcher.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Decouples the decision loop (in [SimMonitorService]) from the executor
 * ([com.arvell.simswitcher.accessibility.SimSwitchAccessibilityService]).
 *
 * Android instantiates the accessibility service itself and we cannot hold a
 * direct reference to it, so the monitor publishes [SwitchRequest]s here and the
 * accessibility service collects them. Process-wide singleton — both live in the
 * same app process.
 */
object SwitchRequestBus {

    data class SwitchRequest(
        val targetSubId: Int,
        val targetLabel: String,
        val reason: String,
        val requestedAt: Long = System.currentTimeMillis(),
    )

    /** Reports back so the monitor can log / surface switch outcomes. */
    data class SwitchResult(
        val targetSubId: Int,
        val success: Boolean,
        val detail: String,
    )

    private val _requests = MutableSharedFlow<SwitchRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<SwitchRequest> = _requests.asSharedFlow()

    private val _results = MutableSharedFlow<SwitchResult>(extraBufferCapacity = 4)
    val results: SharedFlow<SwitchResult> = _results.asSharedFlow()

    /** True while an accessibility service instance is bound and able to switch. */
    @Volatile
    var accessibilityConnected: Boolean = false

    suspend fun requestSwitch(request: SwitchRequest) = _requests.emit(request)

    suspend fun reportResult(result: SwitchResult) = _results.emit(result)
}
