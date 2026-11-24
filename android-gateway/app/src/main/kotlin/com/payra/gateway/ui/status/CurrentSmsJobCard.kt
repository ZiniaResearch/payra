package com.payra.gateway.ui.status

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
import com.payra.gateway.storage.CurrentSmsJob
import com.payra.gateway.storage.SmsJobState

@Composable
internal fun CurrentSmsJobCard(
    job: CurrentSmsJob,
    serverAddress: String?,
    reportError: String?,
    modifier: Modifier = Modifier,
) {
    val status = when (job.state) {
        null -> R.string.gateway_job_idle
        SmsJobState.QUEUED -> R.string.gateway_job_waiting
        SmsJobState.SENDING -> R.string.gateway_job_sending
        SmsJobState.SENT, SmsJobState.FAILED, SmsJobState.PARTIAL, SmsJobState.UNKNOWN, SmsJobState.TOO_LONG ->
            R.string.gateway_job_result_pending
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.gateway_current_job), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge,
            )
            if (job.serverUrl != null && job.serverUrl != serverAddress) {
                Text(stringResource(R.string.gateway_job_original_server, job.serverUrl))
            } else if (status == R.string.gateway_job_result_pending && reportError != null) {
                Text(
                    text = stringResource(R.string.gateway_job_report_rejected, reportError),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
