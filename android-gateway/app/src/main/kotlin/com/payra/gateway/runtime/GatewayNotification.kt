package com.payra.gateway.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.payra.gateway.R
import com.payra.gateway.ui.MainActivity

internal class GatewayNotification(private val context: Context) {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.gateway_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun build(phase: GatewayPhase, ongoing: Boolean = true): Notification {
        val openApp = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gateway_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(phase.titleRes))
            .setContentIntent(PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun update(phase: GatewayPhase) {
        postIfAllowed(ID, phase, ongoing = true)
    }

    fun showAttention(phase: GatewayPhase) {
        postIfAllowed(ATTENTION_ID, phase, ongoing = false)
    }

    fun clearAttention() {
        manager.cancel(ATTENTION_ID)
    }

    private fun postIfAllowed(id: Int, phase: GatewayPhase, ongoing: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, build(phase, ongoing))
        } catch (_: SecurityException) {
            // Notification access can change between the check and the post.
        }
    }

    companion object {
        const val ID = 1
        private const val ATTENTION_ID = 2
        private const val CHANNEL_ID = "gateway_status"
    }
}

internal val GatewayPhase.titleRes: Int
    get() = when (this) {
        GatewayPhase.STOPPED -> R.string.gateway_runtime_stopped
        GatewayPhase.STARTING -> R.string.gateway_runtime_starting
        GatewayPhase.CHECKING_SIM -> R.string.gateway_runtime_checking_sim
        GatewayPhase.RUNNING -> R.string.gateway_runtime_running
        GatewayPhase.WAITING_FOR_SIM -> R.string.gateway_runtime_waiting_for_sim
        GatewayPhase.WAITING_FOR_SERVICE -> R.string.gateway_runtime_waiting_for_service
        GatewayPhase.SIM_STATUS_UNAVAILABLE -> R.string.gateway_runtime_sim_status_unavailable
        GatewayPhase.PERMISSION_REQUIRED -> R.string.gateway_sending_unavailable
        GatewayPhase.START_FAILED -> R.string.gateway_runtime_start_failed
        GatewayPhase.SETTINGS_UNAVAILABLE -> R.string.gateway_runtime_settings_unavailable
    }
