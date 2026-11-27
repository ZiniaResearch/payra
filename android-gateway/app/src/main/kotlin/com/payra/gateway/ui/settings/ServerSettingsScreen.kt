package com.payra.gateway.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.payra.gateway.BuildConfig
import com.payra.gateway.R
import com.payra.gateway.network.GatewayServer
import com.payra.gateway.network.HeartbeatInterval
import com.payra.gateway.permissions.GatewayPermission
import com.payra.gateway.ui.InlineLoadingLabel
import com.payra.gateway.ui.permissions.openGatewayAppSettings
import com.payra.gateway.ui.permissions.rememberGatewayPermissionRequest
import okhttp3.HttpUrl

@Composable
internal fun ServerSettingsScreen(
    serverAddress: String?,
    hasGatewayKey: Boolean,
    heartbeat: HeartbeatInterval,
    isFirstSetup: Boolean,
    isSaving: Boolean,
    saveFailed: Boolean,
    onSave: (HttpUrl, HeartbeatInterval, String?) -> Unit,
    onSkip: () -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable(serverAddress) { mutableStateOf(serverAddress.orEmpty()) }
    var interval by rememberSaveable(heartbeat) { mutableStateOf(heartbeat) }
    var invalidUrl by rememberSaveable { mutableStateOf(false) }
    // The draft credential stays out of Android's saved instance state.
    var key by remember { mutableStateOf("") }
    var invalidKey by remember { mutableStateOf(false) }

    ServerSettingsContent(
        state = ServerSettingsUiState(
            url = url,
            key = key,
            canKeepKey = hasGatewayKey && url.trim() == serverAddress,
            interval = interval,
            isFirstSetup = isFirstSetup,
            hasServer = serverAddress != null,
            isSaving = isSaving,
            invalidUrl = invalidUrl,
            invalidKey = invalidKey,
            saveFailed = saveFailed,
        ),
        onUrlChange = {
            url = it
            invalidUrl = false
            invalidKey = false
        },
        onKeyChange = {
            key = it
            invalidKey = false
        },
        onIntervalChange = { interval = it },
        onSave = {
            if (!isSaving) {
                val baseUrl = GatewayServer.parseBaseUrl(url)
                invalidUrl = baseUrl == null
                val newKey = key.trim().takeIf { it.isNotEmpty() }
                val canKeepKey = hasGatewayKey && baseUrl?.toString() == serverAddress
                invalidKey = if (newKey == null) !canKeepKey else !GatewayServer.isValidKey(newKey)
                if (baseUrl != null && !invalidKey) onSave(baseUrl, interval, newKey)
            }
        },
        onSkip = onSkip,
        onRemove = onRemove,
        onBack = onBack.takeUnless { isFirstSetup },
        localNetworkContent = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                LocalNetworkAccess()
            }
        },
    )
}

private class ServerSettingsUiState(
    val url: String,
    val key: String,
    val canKeepKey: Boolean,
    val interval: HeartbeatInterval,
    val isFirstSetup: Boolean,
    val hasServer: Boolean,
    val isSaving: Boolean,
    val invalidUrl: Boolean,
    val invalidKey: Boolean,
    val saveFailed: Boolean,
)

@Composable
private fun ServerSettingsContent(
    state: ServerSettingsUiState,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onIntervalChange: (HeartbeatInterval) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    onRemove: () -> Unit,
    onBack: (() -> Unit)?,
    localNetworkContent: @Composable () -> Unit,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val title = if (state.isFirstSetup) R.string.gateway_set_up_server else R.string.gateway_server_connection
    SettingsPage(title = title, onBack = onBack, isSaving = state.isSaving) {
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSaving,
            singleLine = true,
            label = { Text(stringResource(R.string.gateway_server_url)) },
            isError = state.invalidUrl,
            supportingText = {
                val hint = when {
                    state.invalidUrl && BuildConfig.DEBUG -> R.string.gateway_server_invalid_debug_url
                    state.invalidUrl -> R.string.gateway_server_invalid_url
                    else -> R.string.gateway_server_url_hint
                }
                Text(stringResource(hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.key,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSaving,
            singleLine = true,
            label = { Text(stringResource(R.string.gateway_key)) },
            visualTransformation = PasswordVisualTransformation(),
            isError = state.invalidKey,
            supportingText = {
                val hint = when {
                    state.invalidKey -> R.string.gateway_key_invalid
                    state.canKeepKey -> R.string.gateway_key_saved
                    else -> R.string.gateway_key_hint
                }
                Text(stringResource(hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
        )

        if (!state.isFirstSetup) {
            HeartbeatPicker(state.interval, !state.isSaving, onIntervalChange)
        }
        localNetworkContent()
        if (state.saveFailed) {
            Text(
                text = stringResource(R.string.gateway_settings_save_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(onClick = onSave, enabled = !state.isSaving) {
            when {
                state.isSaving -> InlineLoadingLabel(stringResource(R.string.gateway_saving))
                state.isFirstSetup -> Text(stringResource(R.string.gateway_server_save_and_continue))
                else -> Text(stringResource(R.string.gateway_save))
            }
        }
        if (state.isFirstSetup) {
            TextButton(onClick = onSkip, enabled = !state.isSaving) {
                Text(stringResource(R.string.gateway_server_skip))
            }
        } else if (state.hasServer) {
            TextButton(onClick = { confirmRemove = true }, enabled = !state.isSaving) {
                Text(stringResource(R.string.gateway_server_remove))
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.gateway_server_remove_title)) },
            text = { Text(stringResource(R.string.gateway_server_remove_description)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) {
                    Text(stringResource(R.string.gateway_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.gateway_cancel)) }
            },
        )
    }
}

@Composable
private fun HeartbeatPicker(
    interval: HeartbeatInterval,
    enabled: Boolean,
    onIntervalChange: (HeartbeatInterval) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.gateway_heartbeat_interval, stringResource(interval.labelRes)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            HeartbeatInterval.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onIntervalChange(option)
                    },
                )
            }
        }
    }
}

private val HeartbeatInterval.labelRes: Int
    get() = when (this) {
        HeartbeatInterval.THIRTY_SECONDS -> R.string.gateway_heartbeat_30
        HeartbeatInterval.ONE_MINUTE -> R.string.gateway_heartbeat_60
        HeartbeatInterval.TWO_MINUTES -> R.string.gateway_heartbeat_120
    }

@Composable
private fun LocalNetworkAccess() {
    val context = LocalContext.current
    val permission = rememberGatewayPermissionRequest(GatewayPermission.LOCAL_NETWORK) {}
    if (permission.access.isGranted) return

    Text(stringResource(R.string.gateway_local_network_description))
    if (permission.access.canRequest) {
        TextButton(onClick = permission.request) {
            Text(stringResource(R.string.gateway_allow_local_network))
        }
    } else {
        TextButton(onClick = { openGatewayAppSettings(context) }) {
            Text(stringResource(R.string.gateway_open_app_settings))
        }
    }
}
