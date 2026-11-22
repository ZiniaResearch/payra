package com.payra.gateway.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.storage.SendingSim
import com.payra.gateway.ui.permissions.PermissionAccess
import com.payra.gateway.ui.simLabel

internal data class GatewaySetupUiState(
    val savedSim: SendingSim?,
    val phoneAccess: PermissionAccess,
    val smsAccess: PermissionAccess,
    val sims: List<ActiveSim>?,
    val serviceBySimId: Map<Int, Boolean>,
    val selectedSimId: Int?,
    val canSubmit: Boolean,
    val isSaving: Boolean,
    val saveFailed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewaySetupContent(
    state: GatewaySetupUiState,
    onAllowSimAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSimSelected: (Int) -> Unit,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val isSettings = onBack != null

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.gateway_settings)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !state.isSaving) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.gateway_back),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSettings) {
                Text(
                    text = stringResource(R.string.gateway_sending_sim),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.gateway_setup_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            if (state.phoneAccess.isGranted) {
                SimSelectionForm(
                    state = state,
                    isSettings = isSettings,
                    onSimSelected = onSimSelected,
                    onSubmit = onSubmit,
                    onOpenAppSettings = onOpenAppSettings,
                )
            } else {
                PhonePermissionSection(
                    state = state,
                    onAllowSimAccess = onAllowSimAccess,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }

            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.gateway_save_sim_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SimSelectionForm(
    state: GatewaySetupUiState,
    isSettings: Boolean,
    onSimSelected: (Int) -> Unit,
    onSubmit: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (!isSettings) {
        Text(text = stringResource(R.string.gateway_setup_description))
        Text(
            text = stringResource(R.string.gateway_active_sims),
            style = MaterialTheme.typography.titleMedium,
        )
    } else if (!state.smsAccess.isGranted) {
        Text(text = stringResource(R.string.gateway_settings_sms_unavailable))
    }

    SimPicker(
        sims = state.sims,
        serviceBySimId = state.serviceBySimId,
        selectedSimId = state.selectedSimId,
        enabled = !state.isSaving,
        onSimSelected = onSimSelected,
    )

    Button(onClick = onSubmit, enabled = state.canSubmit && !state.isSaving) {
        if (state.isSaving) {
            InlineLoadingLabel(text = stringResource(R.string.gateway_saving_sim))
        } else {
            val label = if (isSettings) R.string.gateway_save else R.string.gateway_continue
            Text(text = stringResource(label))
        }
    }
    if (!isSettings && state.smsAccess.wasDenied) {
        Text(
            text = stringResource(R.string.gateway_sms_permission_denied),
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onOpenAppSettings) {
            Text(text = stringResource(R.string.gateway_open_app_settings))
        }
    }
}

@Composable
private fun PhonePermissionSection(
    state: GatewaySetupUiState,
    onAllowSimAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val savedSim = state.savedSim
    if (savedSim != null) {
        Text(text = simLabel(savedSim.slotNumber, savedSim.carrierName))
    }
    Text(text = stringResource(R.string.gateway_sim_access_required))
    if (state.phoneAccess.canRequest) {
        Button(onClick = onAllowSimAccess, enabled = !state.isSaving) {
            Text(text = stringResource(R.string.gateway_allow_sim_access))
        }
    }
    if (savedSim != null || state.phoneAccess.wasDenied) {
        TextButton(onClick = onOpenAppSettings, enabled = !state.isSaving) {
            Text(text = stringResource(R.string.gateway_open_app_settings))
        }
    }
}
