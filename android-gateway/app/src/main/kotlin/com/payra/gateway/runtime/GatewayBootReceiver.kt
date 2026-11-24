package com.payra.gateway.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.payra.gateway.permissions.hasRequiredGatewayPermissions
import com.payra.gateway.storage.GatewayRunPreference
import com.payra.gateway.storage.GatewaySettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class GatewayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        // The broadcast owns this job until finish(), even when the app UI is closed.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(5_000) { restoreGateway(app) }
            } catch (error: IOException) {
                Log.w("GatewayBootReceiver", "Could not read saved gateway settings at boot", error)
                if (GatewayRuntime.state.value.phase == GatewayPhase.STOPPED) {
                    GatewayRuntime.publish(GatewayRuntimeState(GatewayPhase.SETTINGS_UNAVAILABLE))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun restoreGateway(context: Context) {
        val configuration = GatewaySettings(context).configuration.first()
        val enabled = configuration.runPreference as? GatewayRunPreference.Enabled ?: return
        if (!configuration.canStart || !hasRequiredGatewayPermissions(context)) return
        if (withContext(Dispatchers.IO) { wasStoppedByUser(context, enabled.sinceMillis) }) return
        GatewayRuntime.start(context, restore = true)
    }

    // Android also uses this exit reason for some other user-initiated app removals.
    // When Android reports a user stop, require a fresh Start before restoring at boot.
    private fun wasStoppedByUser(context: Context, sinceMillis: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 0).any { exit ->
            exit.timestamp >= sinceMillis && exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED
        }
    }
}
