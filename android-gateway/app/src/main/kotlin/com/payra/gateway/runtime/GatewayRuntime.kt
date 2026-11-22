package com.payra.gateway.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import com.payra.gateway.permissions.hasRequiredGatewayPermissions
import com.payra.gateway.sms.ActiveSim
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GatewayPhase(val isMonitoring: Boolean = false) {
    STOPPED,
    STARTING,
    CHECKING_SIM(true),
    RUNNING(true),
    WAITING_FOR_SIM(true),
    WAITING_FOR_SERVICE(true),
    SIM_STATUS_UNAVAILABLE(true),
    PERMISSION_REQUIRED,
    START_FAILED,
    SETTINGS_UNAVAILABLE,
}

internal data class GatewayRuntimeState(
    val phase: GatewayPhase = GatewayPhase.STOPPED,
    val subscriptionId: Int? = null,
    val activeSim: ActiveSim? = null,
    val isPausing: Boolean = false,
    val pauseFailed: Boolean = false,
)

internal object GatewayRuntime {
    private val current = MutableStateFlow(GatewayRuntimeState())
    val state = current.asStateFlow()

    @MainThread
    fun start(context: Context, restore: Boolean = false) {
        if (current.value.phase.isMonitoring || current.value.phase == GatewayPhase.STARTING) return
        if (!hasRequiredGatewayPermissions(context)) {
            publish(GatewayRuntimeState(GatewayPhase.PERMISSION_REQUIRED))
            return
        }

        publish(GatewayRuntimeState(GatewayPhase.STARTING))
        val intent = Intent(context, GatewayService::class.java)
            .setAction(if (restore) GatewayService.ACTION_RESTORE else GatewayService.ACTION_START)
        try {
            context.startForegroundService(intent)
        } catch (error: IllegalStateException) {
            startFailed(error)
        } catch (error: SecurityException) {
            startFailed(error)
        }
    }

    @MainThread
    fun pause(context: Context) {
        val before = current.value
        if (!before.phase.isMonitoring || before.isPausing) return
        publish(before.copy(isPausing = true, pauseFailed = false))
        try {
            context.startService(Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_PAUSE))
        } catch (error: IllegalStateException) {
            pauseFailed(before, error)
        } catch (error: SecurityException) {
            pauseFailed(before, error)
        }
    }

    @MainThread
    fun refreshNotification(context: Context) {
        val phase = current.value.phase
        if (phase.isMonitoring) GatewayNotification(context).update(phase)
    }

    @MainThread
    fun publish(state: GatewayRuntimeState) {
        current.value = state
    }

    private fun startFailed(error: Exception) {
        Log.w("GatewayRuntime", "Android rejected the gateway start", error)
        publish(GatewayRuntimeState(GatewayPhase.START_FAILED))
    }

    private fun pauseFailed(before: GatewayRuntimeState, error: Exception) {
        Log.w("GatewayRuntime", "Android rejected the pause command", error)
        publish(before.copy(isPausing = false, pauseFailed = true))
    }
}
