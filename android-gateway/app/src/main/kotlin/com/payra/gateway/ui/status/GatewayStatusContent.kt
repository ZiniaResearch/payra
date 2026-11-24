package com.payra.gateway.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.network.GatewayConnectionStatus
import com.payra.gateway.runtime.GatewayPhase
import com.payra.gateway.runtime.titleRes
import com.payra.gateway.ui.permissions.PermissionAccess

internal data class GatewayStatusUiState(
    val simLabel: String,
    val serviceLabel: String,
    val phoneAccess: PermissionAccess,
    val smsAccess: PermissionAccess,
    val notificationAccess: PermissionAccess?,
    val phase: GatewayPhase,
    val serverAddress: String?,
    val connectionStatus: GatewayConnectionStatus,
    val lastWorkNotice: String?,
    val savedJobCount: Int?,
    val isPaused: Boolean,
    val isPausing: Boolean,
    val pauseFailed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayStatusContent(
    state: GatewayStatusUiState,
    onOpenSettings: () -> Unit,
    onConfigureServer: () -> Unit,
    onAllowSimAccess: () -> Unit,
    onAllowSms: () -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.gateway_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.phoneAccess.isGranted || !state.smsAccess.isGranted) {
                PermissionWarning(
                    phoneAccess = state.phoneAccess,
                    smsAccess = state.smsAccess,
                    onAllowSimAccess = onAllowSimAccess,
                    onAllowSms = onAllowSms,
                    onOpenAppSettings = onOpenAppSettings,
                )
            } else {
                GatewayOperationCard(
                    state = state,
                    onStart = onStart,
                    onPause = onPause,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ServerConnectionCard(
                serverAddress = state.serverAddress,
                status = state.connectionStatus,
                lastWorkNotice = state.lastWorkNotice,
                savedJobCount = state.savedJobCount,
                onConfigureServer = onConfigureServer,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.gateway_sending_sim),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(text = state.simLabel, style = MaterialTheme.typography.titleLarge)
                    Text(text = state.serviceLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }

            val notificationAccess = state.notificationAccess
            if (state.phase.isMonitoring && notificationAccess != null && !notificationAccess.isGranted) {
                Text(
                    text = stringResource(R.string.gateway_notifications_optional),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (notificationAccess.canRequest) {
                    TextButton(onClick = onAllowNotifications) {
                        Text(text = stringResource(R.string.gateway_allow_notifications))
                    }
                } else {
                    TextButton(onClick = onOpenAppSettings) {
                        Text(text = stringResource(R.string.gateway_open_app_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewayOperationCard(
    state: GatewayStatusUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val showPaused = phase == GatewayPhase.STOPPED && state.isPaused
    val title = if (showPaused) R.string.gateway_runtime_paused else phase.titleRes
    val description = when (phase) {
        GatewayPhase.STOPPED -> if (showPaused) {
            R.string.gateway_runtime_paused_description
        } else {
            R.string.gateway_runtime_stopped_description
        }
        GatewayPhase.STARTING, GatewayPhase.CHECKING_SIM, GatewayPhase.PERMISSION_REQUIRED -> null
        GatewayPhase.RUNNING -> R.string.gateway_runtime_running_description
        GatewayPhase.WAITING_FOR_SIM -> R.string.gateway_runtime_waiting_for_sim_description
        GatewayPhase.WAITING_FOR_SERVICE -> R.string.gateway_runtime_waiting_for_service_description
        GatewayPhase.SIM_STATUS_UNAVAILABLE -> R.string.gateway_runtime_sim_status_unavailable_description
        GatewayPhase.START_FAILED -> R.string.gateway_runtime_start_failed_description
        GatewayPhase.SETTINGS_UNAVAILABLE -> R.string.gateway_runtime_settings_unavailable_description
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.serverAddress == null) {
                Text(text = stringResource(R.string.gateway_server_required_to_start))
            } else if (description != null) {
                Text(text = stringResource(description), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.pauseFailed) {
                Text(
                    text = stringResource(R.string.gateway_runtime_pause_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (phase.isMonitoring) {
                val label = if (state.isPausing) R.string.gateway_runtime_pausing else R.string.gateway_runtime_pause
                TextButton(onClick = onPause, enabled = !state.isPausing) {
                    Text(text = stringResource(label))
                }
            } else if (phase != GatewayPhase.PERMISSION_REQUIRED) {
                Button(onClick = onStart, enabled = phase != GatewayPhase.STARTING && state.serverAddress != null) {
                    Text(text = stringResource(R.string.gateway_runtime_start))
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionCard(
    serverAddress: String?,
    status: GatewayConnectionStatus,
    lastWorkNotice: String?,
    savedJobCount: Int?,
    onConfigureServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.gateway_server_connection), style = MaterialTheme.typography.labelLarge)
            val title = if (serverAddress == null) R.string.gateway_server_address_needed else status.titleRes
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
            )
            if (serverAddress == null) {
                TextButton(onClick = onConfigureServer) {
                    Text(stringResource(R.string.gateway_set_up_server))
                }
            } else {
                Text(serverAddress, style = MaterialTheme.typography.bodyMedium)
                if (savedJobCount != null) {
                    Text(
                        text = stringResource(R.string.gateway_saved_job_count, savedJobCount),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (lastWorkNotice != null) {
                    Text(
                        text = stringResource(R.string.gateway_last_work_notice, lastWorkNotice),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionWarning(
    phoneAccess: PermissionAccess,
    smsAccess: PermissionAccess,
    onAllowSimAccess: () -> Unit,
    onAllowSms: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.gateway_sending_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!phoneAccess.isGranted) {
                Text(text = stringResource(R.string.gateway_phone_permission_required))
                if (phoneAccess.canRequest) {
                    Button(onClick = onAllowSimAccess) {
                        Text(text = stringResource(R.string.gateway_allow_sim_access))
                    }
                }
            }
            if (!smsAccess.isGranted) {
                Text(text = stringResource(R.string.gateway_sms_permission_required))
                if (smsAccess.canRequest) {
                    Button(onClick = onAllowSms) {
                        Text(text = stringResource(R.string.gateway_allow_sms))
                    }
                }
            }
            TextButton(onClick = onOpenAppSettings) {
                Text(text = stringResource(R.string.gateway_open_app_settings))
            }
        }
    }
}
