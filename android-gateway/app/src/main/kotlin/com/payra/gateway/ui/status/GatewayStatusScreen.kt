package com.payra.gateway.ui.status

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
import com.payra.gateway.R
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.sms.readActiveSims
import com.payra.gateway.storage.SendingSim
import com.payra.gateway.ui.permissions.GatewayPermission
import com.payra.gateway.ui.permissions.openGatewayAppSettings
import com.payra.gateway.ui.permissions.rememberGatewayPermissionRequest
import com.payra.gateway.ui.rememberSimServiceStates
import com.payra.gateway.ui.simLabel

@Composable
internal fun GatewayStatusScreen(sendingSim: SendingSim, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var activeSims by remember(context) { mutableStateOf<List<ActiveSim>?>(null) }
    var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
    val phonePermission = rememberGatewayPermissionRequest(GatewayPermission.PHONE) {
        permissionRequestInFlight = false
        activeSims = readActiveSims(context)
    }
    val smsPermission = rememberGatewayPermissionRequest(GatewayPermission.SMS) {
        permissionRequestInFlight = false
    }

    fun requestPermission(launch: () -> Unit) {
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        launch()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        activeSims = readActiveSims(context)
    }

    val visibleSims = if (phonePermission.access.isGranted) activeSims else null
    val activeSim = visibleSims?.firstOrNull { it.subscriptionId == sendingSim.subscriptionId }
    val serviceStates by rememberSimServiceStates(listOfNotNull(activeSim?.subscriptionId))
    val inService = serviceStates[sendingSim.subscriptionId]

    val slotNumber = activeSim?.slotNumber ?: sendingSim.slotNumber
    val carrierName = if (activeSim != null) activeSim.carrierName else sendingSim.carrierName
    val serviceLabel = when {
        !phonePermission.access.isGranted -> R.string.gateway_sim_service_unavailable
        visibleSims == null -> R.string.gateway_sim_service_unknown
        activeSim == null -> R.string.gateway_status_sim_missing
        inService == true -> R.string.gateway_sim_in_service
        inService == false -> R.string.gateway_sim_no_service
        else -> R.string.gateway_sim_service_unknown
    }

    GatewayStatusContent(
        state = GatewayStatusUiState(
            simLabel = simLabel(slotNumber, carrierName),
            serviceLabel = stringResource(serviceLabel),
            phoneAccess = phonePermission.access,
            smsAccess = smsPermission.access,
        ),
        onOpenSettings = onOpenSettings,
        onAllowSimAccess = { requestPermission(phonePermission.request) },
        onAllowSms = { requestPermission(smsPermission.request) },
        onOpenAppSettings = { openGatewayAppSettings(context) },
    )
}
