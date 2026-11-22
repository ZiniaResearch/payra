package com.payra.gateway.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.payra.gateway.sms.watchSimService

@Composable
internal fun rememberSimServiceStates(
    subscriptionIds: List<Int>,
    refreshVersion: Int = 0,
): State<Map<Int, Boolean>> {
    val context = LocalContext.current
    val serviceStates = remember(context, subscriptionIds, refreshVersion) {
        mutableStateOf(emptyMap<Int, Boolean>())
    }

    LifecycleResumeEffect(context, subscriptionIds, refreshVersion) {
        val stopWatchers = subscriptionIds.mapNotNull { id ->
            watchSimService(context, id) { inService ->
                serviceStates.value = serviceStates.value + (id to inService)
            }
        }

        onPauseOrDispose {
            stopWatchers.forEach { stop -> stop() }
            serviceStates.value = emptyMap()
        }
    }

    return serviceStates
}
