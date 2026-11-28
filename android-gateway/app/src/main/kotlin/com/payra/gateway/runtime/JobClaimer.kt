package com.payra.gateway.runtime

import android.util.Log
import com.payra.gateway.network.ClaimResult
import com.payra.gateway.network.GatewayApi
import com.payra.gateway.network.GatewayServer
import com.payra.gateway.storage.SmsJob
import com.payra.gateway.storage.SmsJobDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class JobClaimer(
    private val api: GatewayApi,
    private val jobs: SmsJobDao,
) {
    suspend fun claim(server: GatewayServer, canClaim: () -> Boolean) {
        var retryDelay = INITIAL_RETRY_MILLIS
        while (canClaim() && !jobs.hasOutstandingJob()) {
            currentCoroutineContext().ensureActive()
            if (!canClaim()) return
            when (val result = api.claimJob(server)) {
                ClaimResult.Empty -> return
                is ClaimResult.Job -> {
                    saveJob(server, result)
                    return
                }
                is ClaimResult.Failure -> {
                    Log.w(TAG, "Claim failed (${result.reason})")
                    if (!result.retryable) return
                }
            }
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    private suspend fun saveJob(server: GatewayServer, result: ClaimResult.Job) {
        val job = SmsJob(
            serverUrl = server.baseUrl.toString(),
            id = result.id,
            attemptNumber = result.attemptNumber,
            recipient = result.recipient,
            message = result.message,
        )
        if (jobs.saveClaim(job)) {
            Log.i(TAG, "Saved job ${job.id} attempt ${job.attemptNumber}")
        }
    }

    companion object {
        private const val TAG = "GatewayClaims"
        private const val INITIAL_RETRY_MILLIS = 2_000L
        private const val MAX_RETRY_MILLIS = 60_000L
    }
}
