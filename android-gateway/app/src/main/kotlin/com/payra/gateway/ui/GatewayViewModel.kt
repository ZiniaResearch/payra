package com.payra.gateway.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.payra.gateway.sms.ActiveSim
import com.payra.gateway.storage.GatewayRunPreference
import com.payra.gateway.storage.GatewaySettings
import com.payra.gateway.storage.SendingSim
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal sealed interface GatewayScreenState {
    data object Loading : GatewayScreenState
    data object LoadFailed : GatewayScreenState
    data class Ready(
        val sendingSim: SendingSim?,
        val isChangingSim: Boolean = false,
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
                GatewayScreenState.Ready(settings.readSendingSim())
            } catch (_: IOException) {
                GatewayScreenState.LoadFailed
            }
        }
    }

    fun setChangingSim(isChanging: Boolean) {
        val current = state as? GatewayScreenState.Ready ?: return
        if (current.isSaving) return
        state = current.copy(isChangingSim = isChanging, saveFailed = false)
    }

    fun saveSim(sim: ActiveSim) {
        val current = state as? GatewayScreenState.Ready ?: return
        if (current.isSaving || (current.sendingSim != null && !current.isChangingSim)) return

        state = current.copy(isSaving = true, saveFailed = false)
        val sendingSim = SendingSim(sim.subscriptionId, sim.slotNumber, sim.carrierName)
        viewModelScope.launch {
            state = try {
                settings.saveSendingSim(sendingSim)
                GatewayScreenState.Ready(sendingSim)
            } catch (_: IOException) {
                current.copy(isSaving = false, saveFailed = true)
            }
        }
    }
}
