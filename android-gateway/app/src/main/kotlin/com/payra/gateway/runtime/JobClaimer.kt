package com.payra.gateway.runtime

import android.database.sqlite.SQLiteException
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
        if (!canClaim()) return

        var result = api.claimJob(server)
        if (result is ClaimResult.Failure && result.retryable) {
            Log.w(TAG, "Claim failed (${result.reason}). Will retry once if ready.")
            delay(2_000)
            if (!canClaim()) return
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
            recipient = result.recipient,
            message = result.message,
        )
        try {
            val rowId = jobs.insert(job)
            if (rowId == -1L) {
                Log.i(TAG, "Job ${job.id} already saved")
            } else {
                Log.i(TAG, "Saved job ${job.id}")
            }
        } catch (error: SQLiteException) {
            Log.w(TAG, "Could not save job ${job.id} (${error.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "GatewayClaims"
    }
}
