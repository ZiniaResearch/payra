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
import com.payra.gateway.ui.permissions.PermissionAccess

internal data class GatewayStatusUiState(
    val simLabel: String,
    val serviceLabel: String,
    val phoneAccess: PermissionAccess,
    val smsAccess: PermissionAccess,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayStatusContent(
    state: GatewayStatusUiState,
    onOpenSettings: () -> Unit,
    onAllowSimAccess: () -> Unit,
    onAllowSms: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Scaffold(
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
            }

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
