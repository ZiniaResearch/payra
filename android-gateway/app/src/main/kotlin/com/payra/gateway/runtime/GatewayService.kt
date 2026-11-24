package com.payra.gateway.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.payra.gateway.network.GatewayApi
import com.payra.gateway.network.GatewayConnectionStatus
import com.payra.gateway.network.GatewayServer
import com.payra.gateway.network.GatewaySocket
import com.payra.gateway.permissions.hasRequiredGatewayPermissions
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.sms.observeActiveSims
import com.payra.gateway.sms.observeSimService
import com.payra.gateway.storage.GatewayDatabase
import com.payra.gateway.storage.GatewayRunPreference
import com.payra.gateway.storage.GatewaySettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class GatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { GatewaySettings(this) }
    private val notification by lazy { GatewayNotification(this) }
    private val networkClient by lazy { OkHttpClient.Builder().followRedirects(false).build() }
    private val gatewaySocket by lazy {
        GatewaySocket(checkNotNull(getSystemService(ConnectivityManager::class.java)), networkClient)
    }
    private val jobClaimer by lazy {
        JobClaimer(GatewayApi(networkClient), GatewayDatabase.getInstance(this).smsJobs())
    }

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

    private suspend fun monitorGateway(manualStart: Boolean) = coroutineScope {
        val initial = settings.configuration.first()
        if (!hasRequiredGatewayPermissions(this@GatewayService)) {
            stopGateway(GatewayPhase.PERMISSION_REQUIRED)
            return@coroutineScope
        }
        if (!initial.canStart || (!manualStart && initial.runPreference !is GatewayRunPreference.Enabled)) {
            stopGateway()
            return@coroutineScope
        }
        if (manualStart) settings.enableGateway()

        var observedSimId: Int? = null
        var observedServer: GatewayServer? = null
        var simJob: Job? = null
        var socketJob: Job? = null
        settings.configuration.collect { configuration ->
            val simId = configuration.sendingSim?.subscriptionId
            val server = configuration.server
            if (configuration.runPreference !is GatewayRunPreference.Enabled || simId == null || server == null) {
                stopGateway()
                return@collect
            }

            // A SIM change only restarts the SIM watcher. URL or heartbeat changes restart the socket.
            if (simId != observedSimId) {
                simJob?.cancelAndJoin()
                observedSimId = simId
                simJob = launch { monitorSim(simId) }
            }
            if (server != observedServer) {
                socketJob?.cancelAndJoin()
                observedServer = server
                GatewayRuntime.publish(
                    GatewayRuntime.state.value.copy(
                        connectionStatus = GatewayConnectionStatus.DISCONNECTED,
                        lastWorkNoticeAtMillis = null,
                    ),
                )
                socketJob = launch { monitorServer(server) }
            }
        }
    }

    private suspend fun monitorServer(server: GatewayServer) = coroutineScope {
        // One claim runs at a time, with room for one more check.
        val claimRequests = Channel<Unit>(Channel.CONFLATED)
        launch {
            claimRequests.consumeEach {
                jobClaimer.claim(server) {
                    isReadyToClaim(GatewayRuntime.state.value) &&
                        hasRequiredGatewayPermissions(this@GatewayService)
                }
            }
        }
        launch {
            GatewayRuntime.state.map(::isReadyToClaim).distinctUntilChanged().collect { ready ->
                if (ready) claimRequests.trySend(Unit)
            }
        }

        gatewaySocket.monitor(
            server = server,
            beforeConnect = {
                val allowed = hasRequiredGatewayPermissions(this@GatewayService)
                if (!allowed) stopGateway(GatewayPhase.PERMISSION_REQUIRED)
                allowed
            },
            onStatus = { status ->
                val previous = GatewayRuntime.state.value
                if (previous.connectionStatus != status) {
                    val next = previous.copy(connectionStatus = status)
                    GatewayRuntime.publish(next)
                    notification.update(next)
                }
            },
            onWorkAvailable = {
                GatewayRuntime.publish(
                    GatewayRuntime.state.value.copy(lastWorkNoticeAtMillis = System.currentTimeMillis()),
                )
                claimRequests.trySend(Unit)
            },
        )
    }

    private fun isReadyToClaim(state: GatewayRuntimeState): Boolean =
        state.phase == GatewayPhase.RUNNING &&
            state.connectionStatus == GatewayConnectionStatus.CONNECTED &&
            !state.isPausing

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
        val next = previous.copy(phase = phase, subscriptionId = subscriptionId, activeSim = activeSim)
        GatewayRuntime.publish(next)
        if (previous.phase != phase) notification.update(next)
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
