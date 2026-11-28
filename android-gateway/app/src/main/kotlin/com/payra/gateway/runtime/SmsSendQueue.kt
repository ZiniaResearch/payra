package com.payra.gateway.runtime

import android.util.Log
import com.payra.gateway.sms.SmsResultReceiver
import com.payra.gateway.sms.SmsSender
import com.payra.gateway.storage.SmsJob
import com.payra.gateway.storage.SmsJobDao
import com.payra.gateway.storage.SmsJobState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SmsSendOptions(val subscriptionId: Int, val maxParts: Int)

internal class SmsSendQueue(
    private val jobs: SmsJobDao,
    private val sender: SmsSender,
) {
    suspend fun monitor(
        serverUrl: String,
        options: Flow<SmsSendOptions?>,
        currentOptions: () -> SmsSendOptions?,
    ) = coroutineScope {
        val nextJob = jobs.observeNextForSending(serverUrl).stateIn(this)
        nextJob.combine(options) { job, ready -> job to ready }
            .distinctUntilChanged()
            .conflate()
            .collect { (job, ready) ->
                if (job == null) return@collect
                if (job.state == SmsJobState.SENDING) {
                    awaitOutcome(job, nextJob)
                } else if (ready != null && job.state == SmsJobState.QUEUED) {
                    submit(job, currentOptions)
                }
            }
    }

    private suspend fun submit(job: SmsJob, currentOptions: () -> SmsSendOptions?) {
        val options = currentOptions() ?: return
        val prepared = try {
            sender.prepare(options.subscriptionId, job.message)
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Could not prepare job ${job.id} (${error.javaClass.simpleName})")
            jobs.rejectQueuedJob(job.serverUrl, job.id, SmsJobState.FAILED, null, System.currentTimeMillis())
            return
        }

        val parts = prepared.parts.size
        if (parts > options.maxParts || parts == 0) {
            val outcome = if (parts == 0) SmsJobState.FAILED else SmsJobState.TOO_LONG
            jobs.rejectQueuedJob(job.serverUrl, job.id, outcome, parts, System.currentTimeMillis())
            Log.w(TAG, "Job ${job.id} rejected ($parts SMS parts, limit ${options.maxParts})")
            return
        }

        val attemptId = UUID.randomUUID().toString()
        val sendingContext = currentCoroutineContext()
        withContext(NonCancellable) {
            if (jobs.beginSend(job.serverUrl, job.id, attemptId, parts, System.currentTimeMillis()) == 0) {
                return@withContext
            }
            // If the service stopped or its settings changed during the Room write, no SMS was submitted yet.
            if (!sendingContext.isActive || currentOptions() != options) {
                jobs.returnToQueue(job.serverUrl, job.id, attemptId)
                return@withContext
            }

            try {
                sender.send(job, attemptId, prepared)
                Log.i(TAG, "Submitted job ${job.id} ($parts SMS parts)")
            } catch (error: RuntimeException) {
                if (error is CancellationException) throw error
                val outcome = when (error) {
                    is SecurityException, is IllegalArgumentException, is UnsupportedOperationException -> SmsJobState.FAILED
                    else -> SmsJobState.UNKNOWN
                }
                jobs.finishAttempt(job.serverUrl, job.id, attemptId, outcome, System.currentTimeMillis())
                Log.w(TAG, "Job ${job.id} outcome: $outcome (${error.javaClass.simpleName})")
            }
        }
    }

    private suspend fun awaitOutcome(job: SmsJob, nextJob: StateFlow<SmsJob?>) {
        val attemptId = checkNotNull(job.attemptId)
        val elapsed = System.currentTimeMillis() - checkNotNull(job.startedAtMillis)
        val remaining = (RESULT_TIMEOUT_MILLIS - elapsed).coerceIn(0L, RESULT_TIMEOUT_MILLIS)
        if (remaining > 0) {
            val resultArrived = withTimeoutOrNull(remaining) {
                // The queue puts the active attempt first until its outcome is recorded.
                nextJob.first { it?.attemptId != attemptId }
                true
            }
            if (resultArrived == true) return
        }

        val updated = jobs.finishAttempt(
            job.serverUrl, job.id, attemptId, SmsJobState.UNKNOWN, System.currentTimeMillis(),
        )
        if (updated > 0) Log.w(TAG, "Job ${job.id} outcome: UNKNOWN")
    }

    companion object {
        private const val TAG = SmsResultReceiver.TAG
        private const val RESULT_TIMEOUT_MILLIS = 120_000L
    }
}
