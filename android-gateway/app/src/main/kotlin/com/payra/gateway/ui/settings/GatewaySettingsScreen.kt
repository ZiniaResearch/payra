package com.payra.gateway.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.storage.GatewayAppearance
import com.payra.gateway.storage.SendingSim
import com.payra.gateway.ui.simLabel

@Composable
internal fun GatewaySettingsScreen(
    sendingSim: SendingSim,
    serverAddress: String?,
    appearance: GatewayAppearance,
    onChooseSim: () -> Unit,
    onConfigureServer: () -> Unit,
    onChangeAppearance: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = R.string.gateway_settings, onBack = onBack) {
        ListItem(
            onClick = onChooseSim,
            supportingContent = { Text(simLabel(sendingSim.slotNumber, sendingSim.carrierName)) },
        ) {
            Text(stringResource(R.string.gateway_sending_sim))
        }
        ListItem(
            onClick = onConfigureServer,
            supportingContent = {
                Text(serverAddress ?: stringResource(R.string.gateway_set_up_server))
            },
        ) {
            Text(stringResource(R.string.gateway_server_connection))
        }
        ListItem(
            onClick = onChangeAppearance,
            supportingContent = { Text(stringResource(appearance.labelRes)) },
        ) {
            Text(stringResource(R.string.gateway_appearance))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    @StringRes title: Int,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = onBack != null || isSaving) {
        if (!isSaving) onBack?.invoke()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, enabled = !isSaving) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.gateway_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}
