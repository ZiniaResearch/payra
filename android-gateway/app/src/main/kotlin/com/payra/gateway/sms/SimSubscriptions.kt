package com.payra.gateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal data class ActiveSim(
    val subscriptionId: Int,
    val slotNumber: Int,
    val carrierName: String?,
)

internal fun readActiveSims(context: Context): List<ActiveSim>? {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }

    val manager =
        context.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()

    val subscriptions =
        try {
            manager.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return emptyList()
        }

    return subscriptions
        .filter { it.simSlotIndex >= 0 }
        .map { subscription ->
            val carrier: CharSequence? = subscription.carrierName
            ActiveSim(
                subscriptionId = subscription.subscriptionId,
                slotNumber = subscription.simSlotIndex + 1,
                carrierName = carrier?.toString()?.trim()?.ifEmpty { null },
            )
        }
}

// Collect on the main thread so Android 8–10 can deliver listener callbacks on its Looper.
@Suppress("DEPRECATION")
internal fun observeActiveSims(context: Context): Flow<List<ActiveSim>?> = callbackFlow {
    val manager = context.getSystemService(SubscriptionManager::class.java)
    if (manager == null) {
        trySend(emptyList())
        close()
        return@callbackFlow
    }

    val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            trySend(readActiveSims(context))
        }
    }
    trySend(readActiveSims(context))
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
        } else {
            manager.addOnSubscriptionsChangedListener(listener)
        }
    } catch (_: SecurityException) {
        trySend(null)
        close()
        return@callbackFlow
    } catch (_: UnsupportedOperationException) {
        trySend(null)
        close()
        return@callbackFlow
    }
    awaitClose { manager.removeOnSubscriptionsChangedListener(listener) }
}
