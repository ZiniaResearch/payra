package com.payra.gateway.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gatewaySettingsStore by preferencesDataStore(name = "gateway_settings")

private val sendingSimIdKey = intPreferencesKey("sending_sim_id")
private val sendingSimSlotKey = intPreferencesKey("sending_sim_slot")
private val sendingSimCarrierKey = stringPreferencesKey("sending_sim_carrier")
private val gatewayEnabledSinceKey = longPreferencesKey("gateway_enabled_since")
private val gatewayPausedKey = booleanPreferencesKey("gateway_paused")

internal data class SendingSim(
    val subscriptionId: Int,
    val slotNumber: Int?,
    val carrierName: String?,
)

internal sealed interface GatewayRunPreference {
    data object NotStarted : GatewayRunPreference
    data object Paused : GatewayRunPreference
    data class Enabled(val sinceMillis: Long) : GatewayRunPreference
}

internal data class GatewayConfiguration(
    val sendingSim: SendingSim?,
    val runPreference: GatewayRunPreference,
)

internal class GatewaySettings(context: Context) {
    private val store = context.applicationContext.gatewaySettingsStore

    val configuration = store.data.map { preferences ->
        val sendingSim = preferences[sendingSimIdKey]?.let { subscriptionId ->
            SendingSim(subscriptionId, preferences[sendingSimSlotKey], preferences[sendingSimCarrierKey])
        }
        val enabledSince = preferences[gatewayEnabledSinceKey]
        val runPreference = when {
            preferences[gatewayPausedKey] == true -> GatewayRunPreference.Paused
            enabledSince != null -> GatewayRunPreference.Enabled(enabledSince)
            else -> GatewayRunPreference.NotStarted
        }
        GatewayConfiguration(sendingSim, runPreference)
    }

    suspend fun readSendingSim(): SendingSim? = configuration.first().sendingSim

    suspend fun enableGateway() {
        store.edit { preferences ->
            preferences[gatewayEnabledSinceKey] = System.currentTimeMillis()
            preferences.remove(gatewayPausedKey)
        }
    }

    suspend fun pauseGateway() {
        store.edit { preferences ->
            preferences.remove(gatewayEnabledSinceKey)
            preferences[gatewayPausedKey] = true
        }
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
