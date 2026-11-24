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
import com.payra.gateway.storage.GatewayAppearance
import com.payra.gateway.ui.settings.AppearanceSettingsScreen
import com.payra.gateway.ui.settings.GatewaySettingsScreen
import com.payra.gateway.ui.settings.ServerSettingsScreen
import com.payra.gateway.ui.setup.GatewaySetupScreen
import com.payra.gateway.ui.status.GatewayStatusScreen
import com.payra.gateway.ui.theme.GatewayTheme

@Composable
internal fun GatewayApp(viewModel: GatewayViewModel) {
    val state = viewModel.state
    val appearance = (state as? GatewayScreenState.Ready)?.configuration?.appearance
        ?: GatewayAppearance.SYSTEM
    GatewayTheme(appearance = appearance) {
        when (state) {
            GatewayScreenState.Loading -> GatewayLoadingScreen()
            GatewayScreenState.LoadFailed -> GatewayLoadingScreen(onRetry = viewModel::loadSettings)
            is GatewayScreenState.Ready -> GatewayReadyScreen(state, viewModel)
        }
    }
}

@Composable
private fun GatewayReadyScreen(state: GatewayScreenState.Ready, viewModel: GatewayViewModel) {
    val configuration = state.configuration
    val sendingSim = configuration.sendingSim
    if (sendingSim == null) {
        GatewaySetupScreen(
            savedSim = null,
            isSaving = state.isSaving,
            saveFailed = state.saveFailed,
            onSaveSim = viewModel::saveSim,
            onBack = null,
        )
        return
    }

    val firstServerSetup = !configuration.serverSetupComplete
    val page = if (firstServerSetup) GatewayPage.SERVER else state.page
    val serverAddress = configuration.serverBaseUrl?.toString()
    when (page) {
        GatewayPage.HOME -> GatewayStatusScreen(
            sendingSim = sendingSim,
            serverAddress = serverAddress,
            isGatewayPaused = viewModel.isGatewayPaused,
            onOpenSettings = { viewModel.openPage(GatewayPage.SETTINGS) },
            onConfigureServer = { viewModel.openPage(GatewayPage.SERVER) },
        )
        GatewayPage.SETTINGS -> GatewaySettingsScreen(
            sendingSim = sendingSim,
            serverAddress = serverAddress,
            appearance = configuration.appearance,
            onChooseSim = { viewModel.openPage(GatewayPage.SENDING_SIM) },
            onConfigureServer = { viewModel.openPage(GatewayPage.SERVER) },
            onChangeAppearance = { viewModel.openPage(GatewayPage.APPEARANCE) },
            onBack = { viewModel.openPage(GatewayPage.HOME) },
        )
        GatewayPage.SENDING_SIM -> GatewaySetupScreen(
            savedSim = sendingSim,
            isSaving = state.isSaving,
            saveFailed = state.saveFailed,
            onSaveSim = viewModel::saveSim,
            onBack = { viewModel.openPage(GatewayPage.SETTINGS) },
        )
        GatewayPage.SERVER -> ServerSettingsScreen(
            serverAddress = serverAddress,
            heartbeat = configuration.heartbeat,
            isFirstSetup = firstServerSetup,
            isSaving = state.isSaving,
            saveFailed = state.saveFailed,
            onSave = viewModel::saveServer,
            onSkip = viewModel::skipServerSetup,
            onRemove = viewModel::removeServer,
            onBack = { viewModel.openPage(state.serverBackPage) },
        )
        GatewayPage.APPEARANCE -> AppearanceSettingsScreen(
            appearance = configuration.appearance,
            isSaving = state.isSaving,
            saveFailed = state.saveFailed,
            onChange = viewModel::saveAppearance,
            onBack = { viewModel.openPage(GatewayPage.SETTINGS) },
        )
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
