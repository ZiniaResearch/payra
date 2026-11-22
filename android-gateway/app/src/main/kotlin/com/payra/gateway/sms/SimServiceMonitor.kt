package com.payra.gateway.sms

import android.content.Context
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi

@MainThread
internal fun watchSimService(
    context: Context,
    subscriptionId: Int,
    onServiceChanged: (Boolean) -> Unit,
): () -> Unit {
    val telephony = context.getSystemService(TelephonyManager::class.java)
        ?.createForSubscriptionId(subscriptionId)
        ?: return {}

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
        return {}
    } catch (_: UnsupportedOperationException) {
        return {}
    }

    return {
        watching = false
        stopListening()
    }
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
