package com.payra.gateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

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
