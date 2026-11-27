package com.payra.gateway.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.payra.gateway.network.HeartbeatInterval
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.storage.GatewayAppearance
import com.payra.gateway.storage.GatewayConfiguration
import com.payra.gateway.storage.GatewayRunPreference
import com.payra.gateway.storage.GatewaySettings
import com.payra.gateway.storage.SendingSim
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

internal enum class GatewayPage {
    HOME,
    SETTINGS,
    SENDING_SIM,
    SERVER,
    SMS_SENDING,
    APPEARANCE,
}

internal sealed interface GatewayScreenState {
    data object Loading : GatewayScreenState
    data object LoadFailed : GatewayScreenState
    data class Ready(
        val configuration: GatewayConfiguration,
        val page: GatewayPage = GatewayPage.HOME,
        val serverBackPage: GatewayPage = GatewayPage.SETTINGS,
        val isSaving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : GatewayScreenState
}

internal class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = GatewaySettings(application)
    private var loadJob: Job? = null

    val isGatewayPaused = settings.configuration
        .map { it.runPreference == GatewayRunPreference.Paused }
        .distinctUntilChanged()
        .catch { error ->
            if (error !is IOException) throw error
            emit(false)
        }

    var state by mutableStateOf<GatewayScreenState>(GatewayScreenState.Loading)
        private set

    fun loadSettings() {
        if (state is GatewayScreenState.Ready || loadJob?.isActive == true) return
        state = GatewayScreenState.Loading
        loadJob = viewModelScope.launch {
            state = try {
                GatewayScreenState.Ready(settings.configuration.first())
            } catch (_: IOException) {
                GatewayScreenState.LoadFailed
            }
        }
    }

    fun openPage(page: GatewayPage) {
        val current = state as? GatewayScreenState.Ready ?: return
        if (current.isSaving) return
        val backPage = if (page == GatewayPage.SERVER) current.page else current.serverBackPage
        state = current.copy(page = page, serverBackPage = backPage, saveFailed = false)
    }

    fun saveSim(sim: ActiveSim) {
        val current = state as? GatewayScreenState.Ready ?: return
        if (current.configuration.sendingSim != null && current.page != GatewayPage.SENDING_SIM) return
        val sendingSim = SendingSim(sim.subscriptionId, sim.slotNumber, sim.carrierName)
        saveSettings { settings.saveSendingSim(sendingSim) }
    }

    fun saveServer(baseUrl: HttpUrl, heartbeat: HeartbeatInterval, newKey: String?) = saveSettings {
        settings.saveServer(baseUrl, heartbeat, newKey)
    }

    fun skipServerSetup() = saveSettings { settings.skipServerSetup() }

    fun removeServer() = saveSettings { settings.removeServer() }

    fun saveMaxSmsParts(maxParts: Int) = saveSettings(page = GatewayPage.SETTINGS) {
        settings.saveMaxSmsParts(maxParts)
    }

    fun saveAppearance(appearance: GatewayAppearance) = saveSettings(page = GatewayPage.APPEARANCE) {
        settings.saveAppearance(appearance)
    }

    private fun saveSettings(
        page: GatewayPage = GatewayPage.HOME,
        save: suspend () -> GatewayConfiguration,
    ) {
        val current = state as? GatewayScreenState.Ready ?: return
        if (current.isSaving) return
        state = current.copy(isSaving = true, saveFailed = false)
        viewModelScope.launch {
            state = try {
                GatewayScreenState.Ready(configuration = save(), page = page)
            } catch (_: IOException) {
                current.copy(isSaving = false, saveFailed = true)
            }
        }
    }
}
