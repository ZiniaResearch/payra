package com.payra.gateway.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.ui.simLabel

@Composable
internal fun SimPicker(
    sims: List<ActiveSim>?,
    serviceBySimId: Map<Int, Boolean>,
    selectedSimId: Int?,
    enabled: Boolean,
    onSimSelected: (Int) -> Unit,
) {
    when {
        sims == null -> InlineLoadingLabel(text = stringResource(R.string.gateway_loading_sims))
        sims.isEmpty() -> Text(stringResource(R.string.gateway_no_active_sims))
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sims.forEach { sim ->
                    val label = simLabel(sim.slotNumber, sim.carrierName)
                    val selected = sim.subscriptionId == selectedSimId
                    val inService = serviceBySimId[sim.subscriptionId]
                    val canSelect = inService == true && enabled
                    val serviceLabel = when (inService) {
                        true -> R.string.gateway_sim_in_service
                        false -> R.string.gateway_sim_no_service
                        null -> R.string.gateway_sim_service_unknown
                    }

                    ListItem(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = selected,
                                enabled = canSelect,
                                onClick = { onSimSelected(sim.subscriptionId) },
                                role = Role.RadioButton,
                            ),
                        leadingContent = {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = canSelect,
                            )
                        },
                        supportingContent = {
                            Text(text = stringResource(serviceLabel))
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                    ) {
                        Text(text = label)
                    }
                }
            }
        }
    }
}
