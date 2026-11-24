package com.payra.gateway.sms

import android.content.Context
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@MainThread
internal fun watchSimService(
    context: Context,
    subscriptionId: Int,
    onServiceChanged: (Boolean) -> Unit,
): (() -> Unit)? {
    val telephony = context.getSystemService(TelephonyManager::class.java)
        ?.createForSubscriptionId(subscriptionId)
        ?: return null

    var watching = true
    val reportState: (ServiceState) -> Unit = { state ->
        if (watching) {
            onServiceChanged(state.state == ServiceState.STATE_IN_SERVICE)
        }
    }

    val stopListening = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerServiceCallback(context, telephony, reportState)
        } else {
            registerLegacyServiceListener(telephony, reportState)
        }
    } catch (_: SecurityException) {
        return null
    } catch (_: UnsupportedOperationException) {
        return null
    }

    return {
        watching = false
        stopListening()
    }
}

internal fun observeSimService(context: Context, subscriptionId: Int): Flow<Boolean?> = callbackFlow {
    val stopWatching = watchSimService(context, subscriptionId) { trySend(it) }
    if (stopWatching == null) {
        trySend(null)
        close()
        return@callbackFlow
    }
    awaitClose { stopWatching() }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun registerServiceCallback(
    context: Context,
    telephony: TelephonyManager,
    reportState: (ServiceState) -> Unit,
): () -> Unit {
    val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            reportState(serviceState)
        }
    }
    telephony.registerTelephonyCallback(context.mainExecutor, callback)
    return { telephony.unregisterTelephonyCallback(callback) }
}

@Suppress("DEPRECATION")
private fun registerLegacyServiceListener(
    telephony: TelephonyManager,
    reportState: (ServiceState) -> Unit,
): () -> Unit {
    val listener = object : android.telephony.PhoneStateListener() {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            reportState(serviceState)
        }
    }
    telephony.listen(listener, android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE)
    return { telephony.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE) }
}
