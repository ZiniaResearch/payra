package com.payra.gateway.ui.status

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.payra.gateway.R
import com.payra.gateway.permissions.GatewayPermission
import com.payra.gateway.runtime.GatewayPhase
import com.payra.gateway.runtime.GatewayRuntime
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.sms.readActiveSims
import com.payra.gateway.storage.SendingSim
import com.payra.gateway.ui.permissions.openGatewayAppSettings
import com.payra.gateway.ui.permissions.rememberGatewayPermissionRequest
import com.payra.gateway.ui.rememberSimServiceStates
import com.payra.gateway.ui.simLabel
import java.util.Date
import kotlinx.coroutines.flow.Flow

@Composable
internal fun GatewayStatusScreen(
    sendingSim: SendingSim,
    serverAddress: String?,
    isGatewayPaused: Flow<Boolean>,
    onOpenSettings: () -> Unit,
    onConfigureServer: () -> Unit,
) {
    val context = LocalContext.current
    val runtime by GatewayRuntime.state.collectAsStateWithLifecycle()
    val isPaused by isGatewayPaused.collectAsStateWithLifecycle(initialValue = false)
    var activeSims by remember(context) { mutableStateOf<List<ActiveSim>?>(null) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    val phonePermission = rememberGatewayPermissionRequest(GatewayPermission.PHONE) {
        permissionRequestInFlight = false
        activeSims = readActiveSims(context)
    }
    val smsPermission = rememberGatewayPermissionRequest(GatewayPermission.SMS) {
        permissionRequestInFlight = false
    }
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberGatewayPermissionRequest(GatewayPermission.NOTIFICATIONS) { granted ->
            permissionRequestInFlight = false
            if (granted) GatewayRuntime.refreshNotification(context)
        }
    } else {
        null
    }

    fun requestPermission(launch: () -> Unit) {
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        launch()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        activeSims = readActiveSims(context)
        GatewayRuntime.refreshNotification(context)
    }

    val canMonitor = phonePermission.access.isGranted && smsPermission.access.isGranted
    val phase = when {
        !canMonitor -> GatewayPhase.PERMISSION_REQUIRED
        runtime.phase == GatewayPhase.PERMISSION_REQUIRED -> GatewayPhase.STOPPED
        else -> runtime.phase
    }

    val serviceOwnsMonitoring = runtime.phase.isMonitoring
    val isWatchingSavedSim = serviceOwnsMonitoring && runtime.subscriptionId == sendingSim.subscriptionId
    val visibleSims = if (phonePermission.access.isGranted) activeSims else null
    val visibleSim = visibleSims?.firstOrNull { it.subscriptionId == sendingSim.subscriptionId }
    val activeSim = if (isWatchingSavedSim) runtime.activeSim else visibleSim
    val serviceStates by rememberSimServiceStates(
        subscriptionIds = if (serviceOwnsMonitoring) emptyList() else listOfNotNull(visibleSim?.subscriptionId),
    )
    val serviceLabel = when {
        !phonePermission.access.isGranted -> R.string.gateway_sim_service_unavailable
        isWatchingSavedSim -> when (runtime.phase) {
            GatewayPhase.RUNNING -> R.string.gateway_sim_in_service
            GatewayPhase.WAITING_FOR_SIM -> R.string.gateway_status_sim_missing
            GatewayPhase.WAITING_FOR_SERVICE -> R.string.gateway_sim_no_service
            GatewayPhase.SIM_STATUS_UNAVAILABLE -> R.string.gateway_sim_service_unavailable
            else -> R.string.gateway_sim_service_unknown
        }
        serviceOwnsMonitoring || visibleSims == null -> R.string.gateway_sim_service_unknown
        visibleSim == null -> R.string.gateway_status_sim_missing
        serviceStates[sendingSim.subscriptionId] == true -> R.string.gateway_sim_in_service
        serviceStates[sendingSim.subscriptionId] == false -> R.string.gateway_sim_no_service
        else -> R.string.gateway_sim_service_unknown
    }
    val slotNumber = activeSim?.slotNumber ?: sendingSim.slotNumber
    val carrierName = if (activeSim != null) activeSim.carrierName else sendingSim.carrierName

    GatewayStatusContent(
        state = GatewayStatusUiState(
            simLabel = simLabel(slotNumber, carrierName),
            serviceLabel = stringResource(serviceLabel),
            phoneAccess = phonePermission.access,
            smsAccess = smsPermission.access,
            notificationAccess = notificationPermission?.access,
            phase = phase,
            serverAddress = serverAddress,
            connectionStatus = runtime.connectionStatus,
            lastWorkNotice = runtime.lastWorkNoticeAtMillis?.let { DateFormat.getTimeFormat(context).format(Date(it)) },
            isPaused = isPaused,
            isPausing = runtime.isPausing,
            pauseFailed = runtime.pauseFailed,
        ),
        onOpenSettings = onOpenSettings,
        onConfigureServer = onConfigureServer,
        onAllowSimAccess = { requestPermission(phonePermission.request) },
        onAllowSms = { requestPermission(smsPermission.request) },
        onAllowNotifications = { notificationPermission?.let { requestPermission(it.request) } },
        onOpenAppSettings = { openGatewayAppSettings(context) },
        onStart = { if (serverAddress != null) GatewayRuntime.start(context) },
        onPause = { GatewayRuntime.pause(context) },
    )
}
