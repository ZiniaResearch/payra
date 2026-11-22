package com.payra.gateway.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.gatewaySettingsStore by preferencesDataStore(name = "gateway_settings")

private val sendingSimIdKey = intPreferencesKey("sending_sim_id")
private val sendingSimSlotKey = intPreferencesKey("sending_sim_slot")
private val sendingSimCarrierKey = stringPreferencesKey("sending_sim_carrier")

internal data class SendingSim(
    val subscriptionId: Int,
    val slotNumber: Int?,
    val carrierName: String?,
)

internal class GatewaySettings(context: Context) {
    private val store = context.applicationContext.gatewaySettingsStore

    suspend fun readSendingSim(): SendingSim? {
        val preferences = store.data.first()
        val subscriptionId = preferences[sendingSimIdKey] ?: return null
        return SendingSim(
            subscriptionId = subscriptionId,
            slotNumber = preferences[sendingSimSlotKey],
            carrierName = preferences[sendingSimCarrierKey],
        )
    }

    suspend fun saveSendingSim(sim: SendingSim) {
        store.edit { preferences ->
            preferences[sendingSimIdKey] = sim.subscriptionId
            if (sim.slotNumber != null) {
                preferences[sendingSimSlotKey] = sim.slotNumber
            } else {
                preferences.remove(sendingSimSlotKey)
            }
            if (sim.carrierName != null) {
                preferences[sendingSimCarrierKey] = sim.carrierName
            } else {
                preferences.remove(sendingSimCarrierKey)
            }
        }
    }
}
