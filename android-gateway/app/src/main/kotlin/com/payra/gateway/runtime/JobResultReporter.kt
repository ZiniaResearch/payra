package com.payra.gateway.runtime

import android.net.Network
import android.util.Log
import com.payra.gateway.network.GatewayApi
import com.payra.gateway.network.GatewayServer
import com.payra.gateway.network.ReportResult
import com.payra.gateway.storage.PendingSmsReport
import com.payra.gateway.storage.SmsJobDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class JobResultReporter(
    private val api: GatewayApi,
    private val jobs: SmsJobDao,
) {
    suspend fun monitor(
        server: GatewayServer,
        networks: Flow<Network?>,
        reconnected: ReceiveChannel<Unit>,
        onRejected: (String?) -> Unit,
        onAcknowledged: () -> Unit,
    ) {
        val serverUrl = server.baseUrl.toString()
        networks.collectLatest { network ->
            if (network == null) return@collectLatest
            jobs.observePendingReport(serverUrl).distinctUntilChanged().collect { key ->
                if (key == null) return@collect
                val report = jobs.freezeReport(serverUrl, key) ?: return@collect
                onRejected(null)
                when (val result = upload(server, report, reconnected)) {
                    ReportResult.Acknowledged -> withContext(NonCancellable) {
                        // A network switch must not interrupt cleanup once Payra has acknowledged the report.
                        if (jobs.acknowledgeReport(serverUrl, key)) {
                            Log.i(TAG, "Acknowledged job ${key.id} attempt ${key.attemptNumber}")
                            onAcknowledged()
                        }
                    }
                    is ReportResult.Failure -> onRejected(result.reason)
                }
            }
        }
    }

    private suspend fun upload(
        server: GatewayServer,
        report: PendingSmsReport,
        reconnected: ReceiveChannel<Unit>,
    ): ReportResult {
        var retryIndex = 0
        while (true) {
            reconnected.tryReceive()
            when (val result = api.reportResult(server, report)) {
                ReportResult.Acknowledged -> return result
                is ReportResult.Failure -> {
                    Log.w(TAG, "Report for job ${report.id} attempt ${report.attemptNumber} failed (${result.reason})")
                    if (!result.retryable) return result
                }
            }

            withTimeoutOrNull(RETRY_DELAYS[retryIndex]) { reconnected.receive() }
            retryIndex = (retryIndex + 1).coerceAtMost(RETRY_DELAYS.lastIndex)
        }
    }

    companion object {
        private const val TAG = "GatewayReports"
        private val RETRY_DELAYS = longArrayOf(5_000, 15_000, 30_000, 60_000)
    }
}
