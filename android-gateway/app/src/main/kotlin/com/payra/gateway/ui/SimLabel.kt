package com.payra.gateway.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.payra.gateway.R

@Composable
internal fun simLabel(slotNumber: Int?, carrierName: String?): String = when {
    slotNumber == null -> stringResource(R.string.gateway_status_saved_sim)
    carrierName == null -> stringResource(R.string.gateway_sim_slot, slotNumber)
    else -> stringResource(R.string.gateway_sim_with_carrier, slotNumber, carrierName)
}
