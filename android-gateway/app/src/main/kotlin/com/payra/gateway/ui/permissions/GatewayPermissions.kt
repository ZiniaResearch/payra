package com.payra.gateway.ui.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.payra.gateway.R
import com.payra.gateway.permissions.GatewayPermission

internal enum class PermissionAccess {
    GRANTED,
    CAN_REQUEST,
    DENIED,
    APP_SETTINGS_REQUIRED;

    val isGranted: Boolean
        get() = this == GRANTED
    val canRequest: Boolean
        get() = this == CAN_REQUEST || this == DENIED
    val wasDenied: Boolean
        get() = this == DENIED || this == APP_SETTINGS_REQUIRED
}

internal data class PermissionRequest(
    val access: PermissionAccess,
    val request: () -> Unit,
)

@Composable
internal fun rememberGatewayPermissionRequest(
    permission: GatewayPermission,
    onResult: (Boolean) -> Unit,
): PermissionRequest {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var access by rememberSaveable(permission) {
        val initialAccess = if (permission.isGranted(context)) {
            PermissionAccess.GRANTED
        } else {
            PermissionAccess.CAN_REQUEST
        }
        mutableStateOf(initialAccess)
    }

    fun refreshAccess(requestDenied: Boolean = false) {
        val wasDenied = requestDenied || access.wasDenied
        access = when {
            permission.isGranted(context) -> PermissionAccess.GRANTED
            !wasDenied -> PermissionAccess.CAN_REQUEST
            activity?.shouldShowRequestPermissionRationale(permission.androidName) == false ->
                PermissionAccess.APP_SETTINGS_REQUIRED
            else -> PermissionAccess.DENIED
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshAccess(requestDenied = !granted)
        onResult(access.isGranted)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshAccess() }

    return PermissionRequest(access) {
        launcher.launch(permission.androidName)
    }
}

internal fun openGatewayAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.gateway_open_app_settings_failed, Toast.LENGTH_LONG).show()
    }
}
