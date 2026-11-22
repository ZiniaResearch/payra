package com.payra.gateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.payra.gateway.R

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val message =
            when (resultCode) {
                Activity.RESULT_OK -> context.getString(R.string.sms_sent)
                SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.sms_no_service)
                SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.sms_radio_off)
                else -> context.getString(R.string.sms_send_failed, resultCode)
            }

        Log.i("PayraSms", message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
