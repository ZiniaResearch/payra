package com.payra.gateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.ui.setup.GatewaySetupScreen
import com.payra.gateway.ui.status.GatewayStatusScreen

@Composable
internal fun GatewayApp(viewModel: GatewayViewModel) {
    when (val state = viewModel.state) {
        GatewayScreenState.Loading -> GatewayLoadingScreen()
        GatewayScreenState.LoadFailed -> GatewayLoadingScreen(onRetry = viewModel::loadSettings)
        is GatewayScreenState.Ready -> {
            val sendingSim = state.sendingSim
            if (sendingSim == null || state.isChangingSim) {
                GatewaySetupScreen(
                    savedSim = sendingSim,
                    isSaving = state.isSaving,
                    saveFailed = state.saveFailed,
                    onSaveSim = viewModel::saveSim,
                    onBack = if (sendingSim != null) {
                        { viewModel.setChangingSim(false) }
                    } else {
                        null
                    },
                )
            } else {
                GatewayStatusScreen(
                    sendingSim = sendingSim,
                    onOpenSettings = { viewModel.setChangingSim(true) },
                )
            }
        }
    }
}

@Composable
private fun GatewayLoadingScreen(onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (onRetry == null) {
            CircularProgressIndicator()
            Text(text = stringResource(R.string.gateway_loading_settings))
        } else {
            Text(
                text = stringResource(R.string.gateway_load_settings_failed),
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.gateway_retry))
            }
        }
    }
}
