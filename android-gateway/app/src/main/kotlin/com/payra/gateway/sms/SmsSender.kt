package com.payra.gateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import com.payra.gateway.storage.SmsJob

internal class SmsSender(private val context: Context) {
    fun prepare(subscriptionId: Int, message: String): PreparedSms {
        val manager = smsManager(subscriptionId)
        return PreparedSms(manager, manager.divideMessage(message))
    }

    fun send(job: SmsJob, attemptId: String, prepared: PreparedSms) {
        if (prepared.parts.size == 1) {
            prepared.manager.sendTextMessage(job.recipient, null, job.message, sentIntent(job, attemptId, 0), null)
            return
        }

        val sentIntents = ArrayList<PendingIntent>(prepared.parts.size)
        for (index in prepared.parts.indices) {
            sentIntents.add(sentIntent(job, attemptId, index))
        }
        prepared.manager.sendMultipartTextMessage(job.recipient, null, prepared.parts, sentIntents, null)
    }

    private fun sentIntent(job: SmsJob, attemptId: String, partIndex: Int): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java)
            .setAction(SmsResultReceiver.ACTION_SMS_SENT)
            .setData(Uri.fromParts("payra-sms", "$attemptId/$partIndex", null))
            .putExtra(SmsResultReceiver.EXTRA_SERVER_URL, job.serverUrl)
            .putExtra(SmsResultReceiver.EXTRA_JOB_ID, job.id)
            .putExtra(SmsResultReceiver.EXTRA_ATTEMPT_ID, attemptId)
            .putExtra(SmsResultReceiver.EXTRA_PART_INDEX, partIndex)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: throw UnsupportedOperationException("SMS service unavailable")
            return manager.createForSubscriptionId(subscriptionId)
        }
        return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }
}

internal data class PreparedSms(val manager: SmsManager, val parts: ArrayList<String>)
