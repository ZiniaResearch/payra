package com.payra.gateway.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.sms.readActiveSims
import com.payra.gateway.storage.SendingSim
import com.payra.gateway.ui.permissions.GatewayPermission
import com.payra.gateway.ui.permissions.openGatewayAppSettings
import com.payra.gateway.ui.permissions.rememberGatewayPermissionRequest
import com.payra.gateway.ui.rememberSimServiceStates

@Composable
internal fun GatewaySetupScreen(
    savedSim: SendingSim?,
    isSaving: Boolean,
    saveFailed: Boolean,
    onSaveSim: (ActiveSim) -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val isSettings = onBack != null
    val savedSimId = savedSim?.subscriptionId

    var activeSims by remember(context) { mutableStateOf<List<ActiveSim>?>(null) }
    var serviceCheckVersion by remember(context) { mutableIntStateOf(0) }
    var draftSimId by rememberSaveable(savedSimId) { mutableStateOf(savedSimId) }
    var smsPermissionRequestSimId by rememberSaveable { mutableStateOf<Int?>(null) }
    var recheckingSimId by rememberSaveable { mutableStateOf<Int?>(null) }

    fun refreshSims() {
        val sims = readActiveSims(context)
        activeSims = sims
        if (sims == null) {
            recheckingSimId = null
        } else if (sims.none { it.subscriptionId == draftSimId }) {
            draftSimId = null
            recheckingSimId = null
        }
    }

    val phonePermission = rememberGatewayPermissionRequest(GatewayPermission.PHONE) { refreshSims() }
    val smsPermission = rememberGatewayPermissionRequest(GatewayPermission.SMS) { granted ->
        val requestedSimId = smsPermissionRequestSimId
        smsPermissionRequestSimId = null
        if (!granted || requestedSimId == null || requestedSimId != draftSimId) {
            return@rememberGatewayPermissionRequest
        }

        refreshSims()
        if (activeSims != null && draftSimId == requestedSimId) {
            recheckingSimId = requestedSimId
            // Wait for a service report collected after the permission prompt.
            serviceCheckVersion += 1
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshSims()
        if (!GatewayPermission.SMS.isGranted(context)) {
            recheckingSimId = null
        }
    }

    val visibleSims = if (phonePermission.access.isGranted) activeSims else null
    val serviceBySimId by rememberSimServiceStates(
        subscriptionIds = visibleSims.orEmpty().map { it.subscriptionId },
        refreshVersion = serviceCheckVersion,
    )
    val selectedSim = visibleSims?.firstOrNull { it.subscriptionId == draftSimId }
    val selectedSimInService = selectedSim?.let { serviceBySimId[it.subscriptionId] }
    val readySim = selectedSim?.takeIf { selectedSimInService == true }

    LaunchedEffect(selectedSim, selectedSimInService, recheckingSimId, isSaving) {
        if (selectedSim == null || selectedSim.subscriptionId != draftSimId) return@LaunchedEffect

        val inService = serviceBySimId[selectedSim.subscriptionId]
        if (inService == false) {
            draftSimId = null
            recheckingSimId = null
        } else if (
            inService == true &&
            selectedSim.subscriptionId == recheckingSimId &&
            !isSaving &&
            GatewayPermission.PHONE.isGranted(context) &&
            GatewayPermission.SMS.isGranted(context)
        ) {
            recheckingSimId = null
            onSaveSim(selectedSim)
        }
    }

    BackHandler(enabled = onBack != null || isSaving) {
        if (!isSaving) onBack?.invoke()
    }

    GatewaySetupContent(
        state = GatewaySetupUiState(
            savedSim = savedSim,
            phoneAccess = phonePermission.access,
            smsAccess = smsPermission.access,
            sims = visibleSims,
            serviceBySimId = serviceBySimId,
            selectedSimId = draftSimId,
            canSubmit = readySim != null &&
                smsPermissionRequestSimId == null &&
                recheckingSimId == null &&
                (isSettings || smsPermission.access.isGranted || smsPermission.access.canRequest),
            isSaving = isSaving,
            saveFailed = saveFailed,
        ),
        onAllowSimAccess = phonePermission.request,
        onOpenAppSettings = { openGatewayAppSettings(context) },
        onSimSelected = { id ->
            draftSimId = id
            recheckingSimId = null
        },
        onSubmit = submit@{
            val sim = readySim ?: return@submit
            if (
                isSaving ||
                sim.subscriptionId != draftSimId ||
                serviceBySimId[sim.subscriptionId] != true ||
                smsPermissionRequestSimId != null ||
                recheckingSimId != null ||
                !GatewayPermission.PHONE.isGranted(context)
            ) {
                return@submit
            }

            if (isSettings || GatewayPermission.SMS.isGranted(context)) {
                onSaveSim(sim)
            } else if (smsPermission.access.canRequest) {
                smsPermissionRequestSimId = sim.subscriptionId
                smsPermission.request()
            }
        },
        onBack = onBack,
    )
}
