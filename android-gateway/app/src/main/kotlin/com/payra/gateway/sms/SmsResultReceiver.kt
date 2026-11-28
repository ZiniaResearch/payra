package com.payra.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.util.Log
import com.payra.gateway.storage.GatewayDatabase
import com.payra.gateway.storage.SmsSendPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val attemptId = intent.getStringExtra(EXTRA_ATTEMPT_ID) ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return

        val part = SmsSendPart(attemptId, partIndex, resultCode)
        val pending = goAsync()
        val appContext = context.applicationContext
        // Android keeps this broadcast active until finish(), even when the service is stopped.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000) {
                    val state = GatewayDatabase.getInstance(appContext).smsJobs().recordPartResult(
                        serverUrl, jobId, part, Activity.RESULT_OK, System.currentTimeMillis(),
                    )
                    if (state != null) Log.i(TAG, "Job $jobId outcome: $state")
                }
            } catch (error: SQLiteException) {
                Log.w(TAG, "Could not save result for job $jobId (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val TAG = "GatewaySms"
        const val ACTION_SMS_SENT = "com.payra.gateway.action.SMS_SENT"
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_ATTEMPT_ID = "attemptId"
        const val EXTRA_PART_INDEX = "partIndex"
    }
}
