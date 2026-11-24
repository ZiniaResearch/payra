package com.payra.gateway.ui.status

import android.telephony.SmsManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.payra.gateway.R
import com.payra.gateway.storage.SmsJobOutcome
import com.payra.gateway.storage.SmsJobState
import com.payra.gateway.storage.SmsQueueOverview

@Composable
internal fun SmsQueueCard(queue: SmsQueueOverview, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.gateway_sms_queue), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.gateway_queued_job_count, queue.queuedCount),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
            )
            if (queue.isSending) {
                Text(stringResource(R.string.gateway_sms_sending_now))
            }
            queue.latestOutcome?.let { outcome ->
                val status = outcomeLabel(outcome)
                if (status != null) {
                    Text(
                        text = stringResource(R.string.gateway_sms_latest_result, status),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    outcome.errorCode?.let { Text(smsErrorLabel(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: SmsJobOutcome): String? = when (outcome.state) {
    SmsJobState.QUEUED, SmsJobState.SENDING -> null
    SmsJobState.SENT -> stringResource(R.string.gateway_sms_sent)
    SmsJobState.FAILED -> stringResource(R.string.gateway_sms_failed)
    SmsJobState.PARTIAL -> stringResource(R.string.gateway_sms_partial)
    SmsJobState.UNKNOWN -> stringResource(R.string.gateway_sms_unknown)
    SmsJobState.TOO_LONG -> stringResource(R.string.gateway_sms_too_long, outcome.partCount ?: 0)
}

@Composable
private fun smsErrorLabel(code: Int): String = when (code) {
    SmsManager.RESULT_ERROR_NO_SERVICE -> stringResource(R.string.gateway_sim_no_service)
    SmsManager.RESULT_ERROR_RADIO_OFF -> stringResource(R.string.gateway_sms_radio_off)
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> stringResource(R.string.gateway_sms_limit_exceeded)
    else -> stringResource(R.string.gateway_sms_android_error, code)
}
