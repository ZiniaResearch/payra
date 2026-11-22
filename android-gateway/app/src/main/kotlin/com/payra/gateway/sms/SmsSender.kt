package com.payra.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.payra.gateway.R
import java.util.UUID

object SmsSender {
    @Suppress("DEPRECATION")
    fun sendTest(
        context: Context,
        recipient: String,
    ) {
        require(recipient.matches(Regex("[+][1-9][0-9]{7,14}"))) {
            "Enter the number with its country code, such as +880…"
        }

        val simId = SubscriptionManager.getDefaultSmsSubscriptionId()
        require(simId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            "Choose the default SMS SIM in Android Settings."
        }

        val smsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requireNotNull(context.getSystemService(SmsManager::class.java))
                    .createForSubscriptionId(simId)
            } else {
                SmsManager.getSmsManagerForSubscriptionId(simId)
            }

        val callback =
            Intent(context, SmsResultReceiver::class.java).apply {
                data = Uri.fromParts("payra-sms-test", UUID.randomUUID().toString(), null)
            }
        val sentIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                callback,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )

        smsManager.sendTextMessage(
            recipient,
            null,
            context.getString(R.string.test_sms_body),
            sentIntent,
            null,
        )
    }
}
