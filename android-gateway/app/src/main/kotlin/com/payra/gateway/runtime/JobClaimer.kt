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
        currentCoroutineContext().ensureActive()
        if (!canClaim() || jobs.hasOutstandingJob()) return

        var result = api.claimJob(server)
        if (result is ClaimResult.Failure && result.retryable) {
            Log.w(TAG, "Claim failed (${result.reason}). Will retry once if ready.")
            delay(2_000)
            if (!canClaim() || jobs.hasOutstandingJob()) return
            result = api.claimJob(server)
        }

        when (result) {
            ClaimResult.Empty -> Log.d(TAG, "No job available")
            is ClaimResult.Failure -> Log.w(TAG, "Claim failed (${result.reason})")
            is ClaimResult.Job -> saveJob(server, result)
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
    }
}
