package com.payra.gateway.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.payra.gateway.network.GatewayServer
import com.payra.gateway.network.HeartbeatInterval
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl

private val Context.gatewaySettingsStore by preferencesDataStore(name = "gateway_settings")

private val sendingSimIdKey = intPreferencesKey("sending_sim_id")
private val sendingSimSlotKey = intPreferencesKey("sending_sim_slot")
private val sendingSimCarrierKey = stringPreferencesKey("sending_sim_carrier")
private val gatewayEnabledSinceKey = longPreferencesKey("gateway_enabled_since")
private val gatewayPausedKey = booleanPreferencesKey("gateway_paused")
private val serverUrlKey = stringPreferencesKey("server_base_url")
private val heartbeatKey = intPreferencesKey("server_heartbeat_seconds")
private val serverSetupCompleteKey = booleanPreferencesKey("server_setup_complete")
private val appearanceKey = stringPreferencesKey("appearance")
private val maxSmsPartsKey = intPreferencesKey("max_sms_parts")
internal const val DEFAULT_SMS_PARTS = 5
internal const val MAX_SMS_PARTS = 255

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

internal enum class GatewayAppearance {
    SYSTEM,
    LIGHT,
    DARK,
}

internal data class GatewayConfiguration(
    val sendingSim: SendingSim?,
    val runPreference: GatewayRunPreference,
    val serverBaseUrl: HttpUrl?,
    val heartbeat: HeartbeatInterval,
    val serverSetupComplete: Boolean,
    val appearance: GatewayAppearance,
    val maxSmsParts: Int,
) {
    val server: GatewayServer?
        get() = serverBaseUrl?.let { GatewayServer(it, heartbeat) }

    val canStart: Boolean
        get() = sendingSim != null && serverBaseUrl != null
}

internal class GatewaySettings(context: Context) {
    private val store = context.applicationContext.gatewaySettingsStore

    val configuration = store.data.map(::readConfiguration)

    private fun readConfiguration(preferences: Preferences): GatewayConfiguration {
        val sendingSim = preferences[sendingSimIdKey]?.let { subscriptionId ->
            SendingSim(subscriptionId, preferences[sendingSimSlotKey], preferences[sendingSimCarrierKey])
        }
        val enabledSince = preferences[gatewayEnabledSinceKey]
        val runPreference = when {
            preferences[gatewayPausedKey] == true -> GatewayRunPreference.Paused
            enabledSince != null -> GatewayRunPreference.Enabled(enabledSince)
            else -> GatewayRunPreference.NotStarted
        }
        val heartbeat = HeartbeatInterval.entries.firstOrNull { it.seconds == preferences[heartbeatKey] }
            ?: HeartbeatInterval.THIRTY_SECONDS
        val appearance = GatewayAppearance.entries.firstOrNull { it.name == preferences[appearanceKey] }
            ?: GatewayAppearance.SYSTEM
        return GatewayConfiguration(
            sendingSim = sendingSim,
            runPreference = runPreference,
            serverBaseUrl = readServerUrl(preferences),
            heartbeat = heartbeat,
            serverSetupComplete = preferences[serverSetupCompleteKey] == true,
            appearance = appearance,
            maxSmsParts = preferences[maxSmsPartsKey]?.takeIf { it in 1..MAX_SMS_PARTS } ?: DEFAULT_SMS_PARTS,
        )
    }

    suspend fun enableGateway() {
        store.edit { preferences ->
            if (readServerUrl(preferences) == null || preferences[sendingSimIdKey] == null) return@edit
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

    suspend fun saveSendingSim(sim: SendingSim): GatewayConfiguration =
        readConfiguration(store.edit { preferences ->
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
        })

    suspend fun saveServer(server: GatewayServer): GatewayConfiguration =
        readConfiguration(store.edit { preferences ->
            // Older installs can have a boot-restore preference from before server setup existed.
            if (readServerUrl(preferences) == null) preferences.remove(gatewayEnabledSinceKey)
            preferences[serverUrlKey] = server.baseUrl.toString()
            preferences[heartbeatKey] = server.heartbeat.seconds
            preferences[serverSetupCompleteKey] = true
        })

    suspend fun skipServerSetup(): GatewayConfiguration =
        readConfiguration(store.edit { it[serverSetupCompleteKey] = true })

    suspend fun removeServer(): GatewayConfiguration =
        readConfiguration(store.edit { preferences ->
            // Clear the address and boot-restore preference in the same write.
            preferences.remove(serverUrlKey)
            preferences.remove(gatewayEnabledSinceKey)
            preferences[gatewayPausedKey] = true
        })

    suspend fun saveAppearance(appearance: GatewayAppearance): GatewayConfiguration =
        readConfiguration(store.edit { it[appearanceKey] = appearance.name })

    suspend fun saveMaxSmsParts(maxParts: Int): GatewayConfiguration {
        require(maxParts in 1..MAX_SMS_PARTS)
        return readConfiguration(store.edit { it[maxSmsPartsKey] = maxParts })
    }

    private fun readServerUrl(preferences: Preferences) =
        preferences[serverUrlKey]?.let { GatewayServer.parseBaseUrl(it) }
}
