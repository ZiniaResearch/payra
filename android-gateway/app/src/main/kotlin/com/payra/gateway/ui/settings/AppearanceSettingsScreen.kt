package com.payra.gateway.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.storage.GatewayAppearance

internal val GatewayAppearance.labelRes: Int
    get() = when (this) {
        GatewayAppearance.SYSTEM -> R.string.gateway_theme_system
        GatewayAppearance.LIGHT -> R.string.gateway_theme_light
        GatewayAppearance.DARK -> R.string.gateway_theme_dark
    }

@Composable
internal fun AppearanceSettingsScreen(
    appearance: GatewayAppearance,
    isSaving: Boolean,
    saveFailed: Boolean,
    onChange: (GatewayAppearance) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = R.string.gateway_appearance, onBack = onBack, isSaving = isSaving) {
        Column(Modifier.selectableGroup()) {
            GatewayAppearance.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == appearance,
                            onClick = { onChange(option) },
                            enabled = !isSaving && option != appearance,
                            role = Role.RadioButton,
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == appearance, onClick = null)
                    Text(stringResource(option.labelRes))
                }
            }
        }
        if (saveFailed) {
            Text(
                text = stringResource(R.string.gateway_settings_save_failed),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
