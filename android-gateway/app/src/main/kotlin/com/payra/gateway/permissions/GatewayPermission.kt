package com.payra.gateway.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

internal enum class GatewayPermission(val androidName: String) {
    PHONE(Manifest.permission.READ_PHONE_STATE),
    SMS(Manifest.permission.SEND_SMS),
    LOCAL_NETWORK(Manifest.permission.ACCESS_LOCAL_NETWORK),
    NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS);

    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(androidName) == PackageManager.PERMISSION_GRANTED
}

internal fun hasRequiredGatewayPermissions(context: Context): Boolean =
    GatewayPermission.PHONE.isGranted(context) && GatewayPermission.SMS.isGranted(context)
