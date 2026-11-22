package com.payra.gateway.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.payra.gateway.permissions.hasRequiredGatewayPermissions
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.sms.observeActiveSims
import com.payra.gateway.sms.observeSimService
import com.payra.gateway.storage.GatewayRunPreference
import com.payra.gateway.storage.GatewaySettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { GatewaySettings(this) }
    private val notification by lazy { GatewayNotification(this) }

    private var monitorJob: Job? = null
    private var pauseJob: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            if (!isForeground) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            pauseGateway()
            return START_STICKY
        }
        if (monitorJob?.isActive == true) return START_STICKY
        if (!enterForeground()) return START_NOT_STICKY

        monitorJob = serviceScope.launch {
            try {
                monitorGateway(manualStart = intent?.action == ACTION_START)
            } catch (error: IOException) {
                Log.w(TAG, "Could not read or update gateway settings", error)
                stopGateway(GatewayPhase.SETTINGS_UNAVAILABLE)
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not monitor the sending SIM", error)
                stopGateway(GatewayPhase.PERMISSION_REQUIRED)
            }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        GatewayRuntime.publish(GatewayRuntimeState(GatewayPhase.STARTING))
        try {
            notification.createChannel()
            ServiceCompat.startForeground(
                this,
                GatewayNotification.ID,
                notification.build(GatewayPhase.STARTING),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
            isForeground = true
            notification.clearAttention()
            return true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Android did not let Payra enter the foreground", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Android did not let Payra enter the foreground", error)
        }
        stopGateway(GatewayPhase.START_FAILED)
        return false
    }

    private suspend fun monitorGateway(manualStart: Boolean) {
        val initial = settings.configuration.first()
        if (!hasRequiredGatewayPermissions(this)) {
            stopGateway(GatewayPhase.PERMISSION_REQUIRED)
            return
        }
        if (initial.sendingSim == null || (!manualStart && initial.runPreference !is GatewayRunPreference.Enabled)) {
            stopGateway()
            return
        }
        if (manualStart) settings.enableGateway()

        settings.configuration.distinctUntilChanged().collectLatest { configuration ->
            val sim = configuration.sendingSim
            if (configuration.runPreference is GatewayRunPreference.Enabled && sim != null) {
                monitorSim(sim.subscriptionId)
            } else {
                stopGateway()
            }
        }
    }

    private suspend fun monitorSim(subscriptionId: Int) {
        showPhase(GatewayPhase.CHECKING_SIM, subscriptionId)
        observeActiveSims(this).distinctUntilChanged().collectLatest { sims ->
            if (!hasRequiredGatewayPermissions(this)) {
                stopGateway(GatewayPhase.PERMISSION_REQUIRED)
                return@collectLatest
            }
            val sim = sims?.firstOrNull { it.subscriptionId == subscriptionId }
            when {
                sims == null -> showPhase(GatewayPhase.SIM_STATUS_UNAVAILABLE, subscriptionId)
                sim == null -> showPhase(GatewayPhase.WAITING_FOR_SIM, subscriptionId)
                else -> watchCellularService(sim)
            }
        }
    }

    private suspend fun watchCellularService(sim: ActiveSim) {
        showPhase(GatewayPhase.CHECKING_SIM, sim.subscriptionId, sim)
        observeSimService(this, sim.subscriptionId).collect { inService ->
            if (!hasRequiredGatewayPermissions(this)) {
                stopGateway(GatewayPhase.PERMISSION_REQUIRED)
                return@collect
            }
            val phase = when (inService) {
                true -> GatewayPhase.RUNNING
                false -> GatewayPhase.WAITING_FOR_SERVICE
                null -> GatewayPhase.SIM_STATUS_UNAVAILABLE
            }
            showPhase(phase, sim.subscriptionId, sim)
        }
    }

    private fun showPhase(phase: GatewayPhase, subscriptionId: Int, activeSim: ActiveSim? = null) {
        val previous = GatewayRuntime.state.value
        GatewayRuntime.publish(previous.copy(phase = phase, subscriptionId = subscriptionId, activeSim = activeSim))
        if (previous.phase != phase) notification.update(phase)
    }

    private fun pauseGateway() {
        if (pauseJob?.isActive == true) return
        pauseJob = serviceScope.launch {
            try {
                settings.pauseGateway()
            } catch (error: IOException) {
                Log.w(TAG, "Could not save the paused state", error)
                val current = GatewayRuntime.state.value
                GatewayRuntime.publish(current.copy(isPausing = false, pauseFailed = true))
            }
        }
    }

    private fun stopGateway(phase: GatewayPhase = GatewayPhase.STOPPED) {
        monitorJob?.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            if (phase == GatewayPhase.PERMISSION_REQUIRED || phase == GatewayPhase.SETTINGS_UNAVAILABLE) {
                notification.showAttention(phase)
            }
        }
        GatewayRuntime.publish(GatewayRuntimeState(phase))
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val phase = GatewayRuntime.state.value.phase
        if (phase.isMonitoring || phase == GatewayPhase.STARTING) {
            GatewayRuntime.publish(GatewayRuntimeState())
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GatewayService"
        internal const val ACTION_START = "com.payra.gateway.action.START"
        internal const val ACTION_RESTORE = "com.payra.gateway.action.RESTORE"
        internal const val ACTION_PAUSE = "com.payra.gateway.action.PAUSE"
    }
}
